package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.analysis.ChangeSet;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.Context;
import com.github.guoyyit.pointeranalysisjava.parser.SourceParser;
import com.github.guoyyit.pointeranalysisjava.persistence.RevisionStore;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers crash recovery and idempotent incremental updates:
 * <ul>
 *   <li>the same change submitted twice is applied exactly once;</li>
 *   <li>a crash before the snapshot commit rolls back to the last consistent
 *       snapshot;</li>
 *   <li>a crash after the snapshot commit but before view publication is
 *       reconciled on restart;</li>
 *   <li>persisted state, published snapshot and query results align at the
 *       last consistent state after restart.</li>
 * </ul>
 * These tests are part of the default suite and are never skipped.
 */
class CrashRecoveryTest {

    private static final String MAIN_V1 = """
            class Main {
              static void main() {
                A a = new A();
              }
            }
            class A {
            }
            """;

    private static final String MAIN_V2 = """
            class Main {
              static void main() {
                A a = new A();
                B b = new B();
              }
            }
            class A {
            }
            class B {
            }
            """;

    private static final String MAIN_V3 = """
            class Main {
              static void main() {
                A a = new A();
                B b = new B();
                C c = new C();
              }
            }
            class A {
            }
            class B {
            }
            class C {
            }
            """;

    private AnalysisService newService(Path dir) {
        return new AnalysisService(new RevisionStore(dir));
    }

    private Map<String, String> sources(String main) {
        return Map.of("Main.java", main);
    }

    private String answerFor(AnalysisService service, String var) {
        return service.queryPointsTo(Context.ROOT,
                AccessPath.local("Main#main/0", var)).answer();
    }

    private Map<String, String> captureAnswers(AnalysisService service) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (String var : List.of("a", "b", "c")) {
            try {
                answers.put(var, answerFor(service, var));
            } catch (Exception ignored) {
                // variable not present in this revision
            }
        }
        return answers;
    }

    /**
     * A store that throws on the Nth snapshot write, simulating a crash at the
     * commit point. All other behaviour delegates to the real store.
     */
    static class FailingStore extends RevisionStore {
        private final int failOnSave;
        private int saveCount = 0;

        FailingStore(Path dir, int failOnSave) {
            super(dir);
            this.failOnSave = failOnSave;
        }

        @Override
        public synchronized void saveSnapshot(PointerAnalysisSnapshot snapshot)
                throws IOException {
            saveCount++;
            if (saveCount == failOnSave) {
                throw new IOException("simulated crash during snapshot write");
            }
            super.saveSnapshot(snapshot);
        }
    }

    @Test
    void duplicateIncrementIsAppliedExactlyOnce(@TempDir Path dir) {
        AnalysisService service = newService(dir);
        service.build(sources(MAIN_V1), AnalysisConfig.defaults());

        service.update(sources(MAIN_V2));
        long revisionAfterFirst = service.currentView().revision();
        assertEquals(2L, revisionAfterFirst, "first update advances to revision 2");

        // Resubmitting the same change must be a no-op.
        service.update(sources(MAIN_V2));
        assertEquals(revisionAfterFirst, service.currentView().revision(),
                "duplicate update must not advance the revision");
        assertTrue(answerFor(service, "b").contains("#b@"),
                "duplicate update must not alter the answer");
    }

    @Test
    void duplicateChangeAfterRestartIsIdempotent(@TempDir Path dir) {
        AnalysisService service = newService(dir);
        service.build(sources(MAIN_V1), AnalysisConfig.defaults());
        service.update(sources(MAIN_V2));
        long revisionBeforeCrash = service.currentView().revision();
        String answerBeforeCrash = answerFor(service, "b");

        AnalysisService recovered = newService(dir);
        assertEquals(revisionBeforeCrash, recovered.currentView().revision(),
                "restart restores the committed revision");
        assertEquals(answerBeforeCrash, answerFor(recovered, "b"),
                "restart restores the committed answer");

        // Retrying the same change after restart is idempotent.
        recovered.update(sources(MAIN_V2));
        assertEquals(revisionBeforeCrash, recovered.currentView().revision(),
                "retry after restart must not advance the revision");
        assertEquals(answerBeforeCrash, answerFor(recovered, "b"),
                "retry after restart must not alter the answer");
    }

    @Test
    void crashBeforeSnapshotCommitRollsBack(@TempDir Path dir) {
        AnalysisService service = newService(dir);
        service.build(sources(MAIN_V1), AnalysisConfig.defaults());
        Map<String, String> answersBefore = captureAnswers(service);

        // This store instance has not saved before; the update's save is #1.
        FailingStore failing = new FailingStore(dir, 1);
        AnalysisService crashed = new AnalysisService(failing);
        assertThrows(RuntimeException.class,
                () -> crashed.update(sources(MAIN_V2)),
                "crash during snapshot write must surface to the caller");

        // Restart with a healthy store: the update never committed.
        AnalysisService recovered = newService(dir);
        assertEquals(1L, recovered.currentView().revision(),
                "snapshot must still be at revision 1 after a pre-commit crash");
        assertEquals(answersBefore, captureAnswers(recovered),
                "query answers must match the last consistent snapshot");

        // The change can now be applied exactly once.
        recovered.update(sources(MAIN_V2));
        assertEquals(2L, recovered.currentView().revision());
        assertTrue(answerFor(recovered, "b").contains("#b@"));
    }

    @Test
    void crashAfterSnapshotCommitIsReconciled(@TempDir Path dir) throws IOException {
        AnalysisService service = newService(dir);
        service.build(sources(MAIN_V1), AnalysisConfig.defaults());
        service.update(sources(MAIN_V2));
        long committedRevision = service.currentView().revision();
        String committedAnswer = answerFor(service, "b");

        // Simulate a crash after the snapshot write but before the pending
        // marker was cleared: re-create the pending marker on disk.
        Program nextProgram = SourceParser.parse(sources(MAIN_V2),
                AnalysisConfig.defaults().parallelism());
        ChangeSet changeSet = ChangeSet.diff(
                SourceParser.parse(sources(MAIN_V1), AnalysisConfig.defaults().parallelism()),
                nextProgram);
        String key = changeSet.fingerprint(committedRevision - 1, nextProgram);
        Files.writeString(dir.resolve("pending-change"),
                key + "\n" + committedRevision + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        AnalysisService recovered = newService(dir);
        assertEquals(committedRevision, recovered.currentView().revision(),
                "restart must keep the committed revision");
        assertEquals(committedAnswer, answerFor(recovered, "b"),
                "restart must keep the committed answer");

        // Retrying the committed change is idempotent.
        recovered.update(sources(MAIN_V2));
        assertEquals(committedRevision, recovered.currentView().revision(),
                "retry must not advance the revision");
    }

    @Test
    void pendingChangeBeforeSnapshotIsDiscarded(@TempDir Path dir) throws IOException {
        AnalysisService service = newService(dir);
        service.build(sources(MAIN_V1), AnalysisConfig.defaults());
        Map<String, String> answersBefore = captureAnswers(service);

        // Simulate a crash before the snapshot write: pending marker exists
        // but the snapshot is still at revision 1.
        Program nextProgram = SourceParser.parse(sources(MAIN_V2),
                AnalysisConfig.defaults().parallelism());
        ChangeSet changeSet = ChangeSet.diff(
                SourceParser.parse(sources(MAIN_V1), AnalysisConfig.defaults().parallelism()),
                nextProgram);
        String key = changeSet.fingerprint(1, nextProgram);
        Files.writeString(dir.resolve("pending-change"),
                key + "\n" + 2L + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        AnalysisService recovered = newService(dir);
        assertEquals(1L, recovered.currentView().revision(),
                "pending marker must be discarded when the snapshot did not advance");
        assertEquals(answersBefore, captureAnswers(recovered),
                "query answers must match the last consistent snapshot");

        // The change can be applied exactly once.
        recovered.update(sources(MAIN_V2));
        assertEquals(2L, recovered.currentView().revision());
    }

    @Test
    void persistedStateSnapshotAndQueriesAlignAfterRestart(@TempDir Path dir) {
        AnalysisService service = newService(dir);
        service.build(sources(MAIN_V1), AnalysisConfig.defaults());
        service.queryPointsTo(Context.ROOT, AccessPath.local("Main#main/0", "a"));
        service.update(sources(MAIN_V2));
        service.queryPointsTo(Context.ROOT, AccessPath.local("Main#main/0", "b"));
        service.queryAlias(Context.ROOT, AccessPath.local("Main#main/0", "a"),
                AccessPath.local("Main#main/0", "b"));

        long revision = service.currentView().revision();
        Map<String, String> answers = captureAnswers(service);
        int historySize = service.history().size();

        AnalysisService recovered = newService(dir);
        assertEquals(revision, recovered.currentView().revision(),
                "published snapshot revision must match persisted state");
        assertEquals(historySize, recovered.history().size(),
                "query history must be fully restored");
        assertEquals(answers, captureAnswers(recovered),
                "query results must match the last consistent state");

        // History is append-only and reproducible.
        int historyAfterCapture = recovered.history().size();
        recovered.queryPointsTo(Context.ROOT, AccessPath.local("Main#main/0", "a"));
        assertEquals(historyAfterCapture + 1, recovered.history().size(),
                "new queries append to the restored history");
    }

    @Test
    void differentChangeIsNotDeduplicated(@TempDir Path dir) {
        AnalysisService service = newService(dir);
        service.build(sources(MAIN_V1), AnalysisConfig.defaults());
        service.update(sources(MAIN_V2));
        long rev2 = service.currentView().revision();

        // A different change must apply, not be deduped.
        service.update(sources(MAIN_V3));
        assertNotEquals(rev2, service.currentView().revision(),
                "a different change must advance the revision");
        assertEquals(3L, service.currentView().revision());
        assertTrue(answerFor(service, "c").contains("#c@"),
                "the new allocation must be visible");

        // Resubmitting v3 is idempotent.
        service.update(sources(MAIN_V3));
        assertEquals(3L, service.currentView().revision());
    }
}
