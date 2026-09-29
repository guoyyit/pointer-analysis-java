package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.service.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import com.github.guoyyit.pointeranalysisjava.snapshot.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashRecoveryTest {

    private AnalysisService service(Path dir) {
        AnalysisConfig config = new AnalysisConfig();
        config.setContextDepth(1);
        config.setStorageDir(dir);
        return new AnalysisService(config);
    }

    @Test
    void stateIsRestoredAfterRestart(@TempDir Path dir) {
        AnalysisService before = service(dir);
        before.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));
        before.queryPointsTo("get", "[a.lang:4]", "p");

        AnalysisService after = service(dir);

        assertEquals(1, after.currentSnapshot().revision());
        assertEquals(List.of("a.lang:2@a"),
                after.queryPointsTo("get", "[a.lang:4]", "p").allocations());
        assertTrue(after.currentSnapshot().sourcePaths().contains("a.lang"));
    }

    @Test
    void appliedIncrementSurvivesRestartAndIsNotRepeated(@TempDir Path dir) {
        AnalysisService before = service(dir);
        before.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));
        String changed = TestFixtures.TWO_CALL_SITES
                .replace("call y = get(b);", "call y = get(a);");
        before.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", changed), "durable-change"));
        long revision = before.currentSnapshot().revision();

        AnalysisService after = service(dir);
        assertEquals(revision, after.currentSnapshot().revision());
        assertTrue(after.currentSnapshot().appliedChanges().contains("durable-change"));

        after.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", changed), "durable-change"));
        assertEquals(revision, after.currentSnapshot().revision());
        assertTrue(after.verifyAgainstFullRebuild().consistent());
    }

    @Test
    void queryHistorySurvivesRestart(@TempDir Path dir) {
        AnalysisService before = service(dir);
        before.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));
        before.queryPointsTo("main", "[]", "x");
        before.queryPointsTo("main", "[]", "y");

        SnapshotStore reopenedStore =
                new SnapshotStore(dir);
        List<SnapshotStore.QueryRecord> history = reopenedStore.queryHistory();

        assertEquals(2, history.size());
        assertEquals(0L, history.get(0).sequence());
        assertEquals(1L, history.get(1).sequence());
        assertEquals("points-to", history.get(0).kind());
        assertEquals(1L, history.get(0).revision());
    }

    @Test
    void furtherIncrementAfterRestartMatchesFullRebuild(@TempDir Path dir) {
        AnalysisService before = service(dir);
        before.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));
        String first = TestFixtures.TWO_CALL_SITES
                .replace("call y = get(b);", "call y = get(a);");
        before.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", first), "first"));

        AnalysisService after = service(dir);
        String second = first.replace("call x = get(a);", "copy x = a;");
        after.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", second), "second"));

        assertTrue(after.verifyAgainstFullRebuild().consistent());
    }
}
