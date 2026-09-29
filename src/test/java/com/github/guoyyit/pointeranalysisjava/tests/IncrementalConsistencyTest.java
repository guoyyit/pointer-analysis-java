package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.model.SourceFile;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalConsistencyTest {

    private AnalysisService service(Path dir) {
        AnalysisConfig config = new AnalysisConfig();
        config.setContextDepth(1);
        config.setStorageDir(dir);
        return new AnalysisService(config);
    }

    @Test
    void singleFileChangeMatchesFullRebuild(@TempDir Path dir) {
        AnalysisService service = service(dir);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));

        String changed = TestFixtures.TWO_CALL_SITES
                .replace("call y = get(b);", "call y = get(a);");
        service.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", changed), "change-1"));

        AnalysisService.VerificationResult verification = service.verifyAgainstFullRebuild();
        assertTrue(verification.consistent(), () -> String.join("\n", verification.mismatches()));
        assertEquals(List.of("a.lang:2@a"),
                service.queryPointsTo("main", "[]", "y").allocations());
    }

    @Test
    void multipleIncrementalRoundsStayConsistent(@TempDir Path dir) {
        AnalysisService service = service(dir);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.FIELD_FLOW));

        String first = TestFixtures.FIELD_FLOW.replace("store x.next = x;", "store x.next = x;");
        service.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", first), "c1"));
        String second = first.replace("load z = y.next;", "copy z = y;");
        service.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", second), "c2"));
        String third = second.replace("call y = id(x);", "copy y = x;");
        service.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", third), "c3"));

        AnalysisService.VerificationResult verification = service.verifyAgainstFullRebuild();
        assertTrue(verification.consistent(), () -> String.join("\n", verification.mismatches()));
        assertEquals(4, service.currentSnapshot().revision());
        assertEquals(List.of("c1", "c2", "c3"),
                List.copyOf(service.currentSnapshot().appliedChanges()));
    }

    @Test
    void changedHelperFileMatchesFullRebuild(@TempDir Path dir) {
        AnalysisService service = service(dir);
        String main = """
                entry void main() {
                  new a = new A();
                  call x = helper(a);
                }
                """;
        String helperOld = """
                void helper(p) {
                  copy q = p;
                  return q;
                }
                """;
        String helperNew = """
                void helper(p) {
                  copy r = p;
                  copy q = r;
                  return q;
                }
                """;
        service.rebuild(List.of(new SourceFile("main.lang", main),
                new SourceFile("helper.lang", helperOld)));
        service.increment(new AnalysisService.SourceChange(
                List.of(new SourceFile("helper.lang", helperNew)), "helper-c1"));

        AnalysisService.VerificationResult verification = service.verifyAgainstFullRebuild();
        assertTrue(verification.consistent(), () -> String.join("\n", verification.mismatches()));
        assertTrue(service.queryPointsTo("main", "[]", "x").allocations()
                .contains("main.lang:2@a"));
    }

    @Test
    void removingPointerFlowIsObserved(@TempDir Path dir) {
        AnalysisService service = service(dir);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));
        String changed = TestFixtures.TWO_CALL_SITES
                .replace("call y = get(b);", "call y = get(a);");
        service.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", changed), "flow-change"));

        assertEquals(List.of("a.lang:2@a"),
                service.queryPointsTo("get", "[a.lang:5]", "p").allocations());
        assertTrue(service.verifyAgainstFullRebuild().consistent());
    }

    @Test
    void removingFieldFlowIsObserved(@TempDir Path dir) {
        AnalysisService service = service(dir);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.FIELD_FLOW));
        String changed = TestFixtures.FIELD_FLOW.replace("store x.next = x;", "");
        service.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", changed), "remove-store"));

        assertTrue(service.queryPointsTo("main", "[]", "z").allocations().isEmpty());
        assertTrue(service.verifyAgainstFullRebuild().consistent());
    }

    @Test
    void addingANewFileIsEquivalentToFullRebuild(@TempDir Path dir) {
        AnalysisService service = service(dir);
        String main = """
                entry void main() {
                  new a = new A();
                  call x = extra(a);
                }
                """;
        service.rebuild(TestFixtures.single("main.lang", main + "void extra(p) {\n  return p;\n}\n"));
        SourceFile newMain = new SourceFile("main.lang", main);
        SourceFile newFile = new SourceFile("extra.lang",
                "void extra(p) {\n  copy q = p;\n  return q;\n}\n");
        service.increment(new AnalysisService.SourceChange(List.of(newMain, newFile), "split"));

        assertTrue(service.queryPointsTo("main", "[]", "x").allocations()
                .contains("main.lang:2@a"));
        assertTrue(service.verifyAgainstFullRebuild().consistent());
    }

    @Test
    void duplicateChangeIdIsNotAppliedTwice(@TempDir Path dir) {
        AnalysisService service = service(dir);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));
        String changed = TestFixtures.TWO_CALL_SITES
                .replace("call y = get(b);", "call y = get(a);");
        List<SourceFile> files = TestFixtures.single("a.lang", changed);

        service.increment(new AnalysisService.SourceChange(files, "idempotent"));
        long revisionAfterFirst = service.currentSnapshot().revision();
        service.increment(new AnalysisService.SourceChange(files, "idempotent"));

        assertEquals(revisionAfterFirst, service.currentSnapshot().revision());
        assertEquals(1, service.currentSnapshot().appliedChanges().size());
    }
}
