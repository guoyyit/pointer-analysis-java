package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSolver;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.Context;
import com.github.guoyyit.pointeranalysisjava.parser.ParseOutcome;
import com.github.guoyyit.pointeranalysisjava.parser.SourceParser;
import com.github.guoyyit.pointeranalysisjava.persistence.RevisionStore;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import com.github.guoyyit.pointeranalysisjava.service.RevisionView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelAnalysisTest {

    private static final int FILES = 64;

    private Map<String, String> manyFiles() {
        Map<String, String> sources = new LinkedHashMap<>();
        StringBuilder main = new StringBuilder("class Main {\n  static void main() {\n");
        for (int i = 0; i < FILES; i++) {
            main.append("    D").append(i).append(" d").append(i)
                    .append(" = Lib.make").append(i).append("();\n");
        }
        main.append("  }\n}\n");
        sources.put("Main.java", main.toString());

        StringBuilder lib = new StringBuilder("class Lib {\n");
        for (int i = 0; i < FILES; i++) {
            lib.append("  static D").append(i).append(" make").append(i).append("() {\n");
            for (int j = 0; j < 200; j++) {
                lib.append("    D").append(i).append(" x").append(j)
                        .append(" = new D").append(i).append("();\n");
            }
            lib.append("    D").append(i).append(" r = x0;\n");
            lib.append("    return r;\n  }\n");
        }
        lib.append("}\n");
        sources.put("Lib.java", lib.toString());

        for (int i = 0; i < FILES; i++) {
            sources.put("d/D" + i + ".java",
                    "class D" + i + " {\n  Object f;\n}\n");
        }
        return sources;
    }

    @Test
    void parallelismChangesExecutionAndProducesIdenticalPrograms() {
        Map<String, String> sources = manyFiles();

        ParseOutcome sequential = SourceParser.parse(sources, 1);
        ParseOutcome parallel = SourceParser.parse(sources, 8);

        assertEquals(sequential.program().classes(), parallel.program().classes());
        assertEquals(sequential.program().classesByFile(),
                parallel.program().classesByFile());
        assertEquals(sequential.program().sourceHashByFile(),
                parallel.program().sourceHashByFile());

        assertEquals(1, sequential.stats().parallelism());
        assertEquals(8, parallel.stats().parallelism());
        assertEquals(FILES + 2, parallel.stats().files());
        assertTrue(parallel.stats().workerThreads().size() >= 2,
                "parallelism=8 must actually use multiple worker threads, got "
                        + parallel.stats().workerThreads());
        assertTrue(
                parallel.stats().workerThreads().stream()
                        .anyMatch(name -> name.startsWith("pointer-analysis-parser-")),
                "workers must be observable parser threads: "
                        + parallel.stats().workerThreads());

        // The analysis result over both parsed programs is identical.
        AnalysisConfig config = new AnalysisConfig(1, 8, "Main", "main");
        PointerAnalysisSnapshot seqSnapshot =
                new PointerAnalysisSolver(config).solve(sequential.program());
        PointerAnalysisSnapshot parSnapshot =
                new PointerAnalysisSolver(config).solve(parallel.program());
        assertEquals(seqSnapshot.varPointsTo(), parSnapshot.varPointsTo());
        assertEquals(seqSnapshot.fieldPointsTo(), parSnapshot.fieldPointsTo());
    }

    @Test
    void serviceStatusObservesConfiguredParallelismAndWorkers(@TempDir Path dir) {
        Map<String, String> sources = manyFiles();
        RevisionStore store = new RevisionStore(dir);
        AnalysisService service = new AnalysisService(store);
        service.build(sources, new AnalysisConfig(1, 8, "Main", "main"));

        AnalysisService.ExecutionStats stats = service.executionStats();
        assertEquals(8, stats.configuredParallelism());
        assertEquals(8, stats.parseParallelism());
        assertTrue(stats.parserWorkerThreads().size() >= 2,
                "build with parallelism=8 must fan parsing out, got "
                        + stats.parserWorkerThreads());

        service.build(sources, new AnalysisConfig(1, 1, "Main", "main"));
        AnalysisService.ExecutionStats single = service.executionStats();
        assertEquals(1, single.configuredParallelism());
        assertEquals(1, single.parserWorkerThreads().size(),
                "parallelism=1 runs parsing on the calling thread only");
    }

    @Test
    void snapshotsAndQueriesAreStableAcrossParallelismAndRepeatedRuns(@TempDir Path dir) {
        Map<String, String> sources = manyFiles();
        Program referenceProgram = SourceParser.parse(sources, 1).program();
        AnalysisConfig config = new AnalysisConfig(1, 4, "Main", "main");
        PointerAnalysisSnapshot reference =
                new PointerAnalysisSolver(config).solve(referenceProgram);

        for (int run = 0; run < 5; run++) {
            Program program = SourceParser.parse(sources, 4).program();
            PointerAnalysisSnapshot snapshot =
                    new PointerAnalysisSolver(config).solve(program);
            assertEquals(reference.varPointsTo(), snapshot.varPointsTo());
            assertEquals(reference.fieldPointsTo(), snapshot.fieldPointsTo());
            for (int i = 0; i < FILES; i++) {
                List<PointerAnalysisSnapshot.PathTarget> targets =
                        snapshot.evaluatePath(null,
                                AccessPath.local("Main#main/0", "d" + i));
                List<PointerAnalysisSnapshot.PathTarget> expected =
                        reference.evaluatePath(null,
                                AccessPath.local("Main#main/0", "d" + i));
                assertEquals(expected, targets, "run " + run + " var d" + i);
            }
        }

        // Two independent persisted builds, regardless of parse scheduling,
        // yield the same query history answers.
        List<String> answersA = buildAndQuery(dir.resolve("a"), sources, 4);
        List<String> answersB = buildAndQuery(dir.resolve("b"), sources, 8);
        assertEquals(answersA, answersB);
    }

    @Test
    void concurrentQueriesDuringUpdatesNeverObserveTornState(@TempDir Path dir)
            throws Exception {
        Map<String, String> v1 = Map.of("Main.java",
                "class Main {\n  static void main() {\n    A a = new A();\n  }\n}\n"
                        + "class A {\n}\n");
        Map<String, String> v2 = Map.of("Main.java",
                "class Main {\n  static void main() {\n    A a = new A();\n"
                        + "    B b = new B();\n  }\n}\nclass A {\n}\nclass B {\n}\n");
        AnalysisService service = new AnalysisService(new RevisionStore(dir));
        service.build(v1, new AnalysisConfig(1, 4, "Main", "main"));

        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> revisionsSeen = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 100; i++) {
                    service.update(i % 2 == 0 ? v2 : v1);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        RevisionView view = service.currentView();
                        long revision = view.revision();
                        revisionsSeen.add(revision);
                        int objects = view.snapshot().varPointsTo().values().stream()
                                .mapToInt(java.util.Set::size).sum();
                        int expected = (revision - 1) % 2 == 0 ? 1 : 2;
                        assertEquals(expected, objects,
                                "torn snapshot at revision " + revision);
                        // Query answers must match the exact revision observed.
                        var targets = view.snapshot().evaluatePath(Context.ROOT,
                                AccessPath.local("Main#main/0", "a"));
                        assertEquals(1, targets.size());
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(revisionsSeen.size() >= 2);
    }

    private List<String> buildAndQuery(Path dir, Map<String, String> sources,
                                       int parallelism) {
        AnalysisService service = new AnalysisService(new RevisionStore(dir));
        service.build(sources, new AnalysisConfig(1, parallelism, "Main", "main"));
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            answers.add(service.queryPointsTo(Context.ROOT,
                    AccessPath.local("Main#main/0", "d" + i)).answer());
        }
        return answers;
    }
}
