package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.SourceFile;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceAndDeterminismTest {

    private AnalysisService service(Path dir, int parallelism) {
        AnalysisConfig config = new AnalysisConfig();
        config.setContextDepth(1);
        config.setParallelism(parallelism);
        config.setStorageDir(dir);
        return new AnalysisService(config);
    }

    private String mainProgram(int helperCount) {
        StringBuilder builder = new StringBuilder();
        builder.append("entry void main() {\n");
        builder.append("  new a = new A();\n");
        for (int i = 0; i < helperCount; i++) {
            builder.append("  call v").append(i).append(" = h").append(i).append("(a);\n");
        }
        builder.append("}\n");
        return builder.toString();
    }

    private String helperProgram(int index) {
        return "void h" + index + "(p) {\n  return p;\n}\n";
    }

    private List<SourceFile> largeProgramFiles(int helperCount) {
        List<SourceFile> files = new ArrayList<>();
        files.add(new SourceFile("main.lang", mainProgram(helperCount)));
        for (int i = 0; i < helperCount; i++) {
            files.add(new SourceFile("h" + i + ".lang", helperProgram(i)));
        }
        return files;
    }

    @Test
    void smallChangeWorkIsProportionalToAffectedRegion(@TempDir Path dir) {
        int helperCount = 200;
        AnalysisService service = service(dir, 1);
        List<SourceFile> files = largeProgramFiles(helperCount);
        service.rebuild(files);

        // Change one small leaf file: only that method's small context is affected.
        SourceFile changedHelper = new SourceFile("h0.lang",
                "void h0(p) {\n  copy q = p;\n  return q;\n}\n");
        long start = System.nanoTime();
        var stats = service.incrementWithStats(new AnalysisService.SourceChange(
                List.of(changedHelper), "tiny"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(service.verifyAgainstFullRebuild().consistent());
        // The re-solved region is a handful of variables, regardless of helperCount: it is
        // proportional to the impact of the change, not to the 200 unrelated helpers.
        assertTrue(stats.affectedVariables() <= 8,
                "affected region should stay tiny, was " + stats.affectedVariables());
        assertTrue(stats.processedVariables() <= 8,
                "solver work should stay tiny, was " + stats.processedVariables());
        assertTrue(stats.constraintCount() > helperCount * 2,
                "program constraint count scales with program size");
        // Loose wall-clock guard for CI stability.
        assertTrue(elapsedMillis < 10_000, "increment took too long: " + elapsedMillis + "ms");
    }

    @Test
    void parallelismDoesNotChangeResults(@TempDir Path dir) {
        List<String> serialAnswers;
        List<String> parallelAnswers;
        try {
            Path serialDir = java.nio.file.Files.createTempDirectory(dir, "pa-serial");
            Path parallelDir = java.nio.file.Files.createTempDirectory(dir, "pa-parallel");
            serialAnswers = answers(service(serialDir, 1));
            parallelAnswers = answers(service(parallelDir, 8));
        } catch (java.io.IOException exception) {
            throw new RuntimeException(exception);
        }
        assertEquals(serialAnswers, parallelAnswers);
    }

    private List<String> answers(AnalysisService service) {
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.FIELD_FLOW));
        String changed = TestFixtures.FIELD_FLOW
                .replace("load z = y.next;", "copy z = y;");
        service.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", changed), "c"));
        List<String> answers = new ArrayList<>();
        answers.add(service.queryPointsTo("main", "[]", "z").toString());
        answers.add(service.queryPointsTo("main", "[]", "y").toString());
        answers.add(Boolean.toString(service.queryAlias("main", "[]",
                AccessPath.of("x", List.of()), AccessPath.of("z", List.of())).mayAlias()));
        return answers;
    }

    @Test
    void sameOperationSequenceProducesSameStateAndHistory(@TempDir Path dir) throws Exception {
        Path first = java.nio.file.Files.createTempDirectory(dir, "run-a");
        Path second = java.nio.file.Files.createTempDirectory(dir, "run-b");

        List<String> historyA = runDeterministicScenario(first);
        List<String> historyB = runDeterministicScenario(second);

        assertEquals(historyA, historyB);
    }

    private List<String> runDeterministicScenario(Path dir) {
        AnalysisService service = service(dir, 4);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));
        service.queryPointsTo("get", "[a.lang:4]", "p");
        String changed = TestFixtures.TWO_CALL_SITES
                .replace("call y = get(b);", "call y = get(a);");
        service.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", changed), "det-1"));
        service.queryPointsTo("main", "[]", "y");
        return service.queryHistory().stream()
                .map(record -> record.sequence() + "|" + record.revision() + "|"
                        + record.kind() + "|" + record.request() + "|" + record.result())
                .toList();
    }

    @Test
    void concurrentInterleavingEndsInDeterministicState(@TempDir Path dir) throws Exception {
        AnalysisService service = service(dir, 4);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.FIELD_FLOW));

        int count = 10;
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < count; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    latch.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                String content = TestFixtures.FIELD_FLOW.replace(
                        "store x.next = x;",
                        "store x.next = x;\n  copy marker" + index + " = x;");
                service.increment(new AnalysisService.SourceChange(
                        TestFixtures.single("a.lang", content), "m" + index));
            });
        }
        latch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(service.verifyAgainstFullRebuild().consistent());
        assertEquals(count + 1, service.currentSnapshot().revision());
    }
}
