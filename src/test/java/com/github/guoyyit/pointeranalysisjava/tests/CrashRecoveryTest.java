package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.Context;
import com.github.guoyyit.pointeranalysisjava.persistence.QueryRecord;
import com.github.guoyyit.pointeranalysisjava.persistence.RevisionStore;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import com.github.guoyyit.pointeranalysisjava.service.RevisionView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void duplicateSubmissionIsIdempotentInProcessAndAfterRestart(@TempDir Path dir)
            throws Exception {
        RevisionStore store = new RevisionStore(dir);
        AnalysisService service = new AnalysisService(store);
        service.build(Map.of("Main.java", MAIN_V1), AnalysisConfig.defaults());
        RevisionView first = service.update(Map.of("Main.java", MAIN_V2));
        assertEquals(2L, first.revision());
        service.queryPointsTo(Context.ROOT, AccessPath.local("Main#main/0", "b"));
        int historyAfterFirst = service.history().size();

        RevisionView resubmittedSameProcess =
                service.update(Map.of("Main.java", MAIN_V2));
        assertEquals(2L, resubmittedSameProcess.revision(),
                "identical sources must not advance the revision");
        assertEquals(historyAfterFirst, service.history().size(),
                "idempotent resubmission must not append history");

        AnalysisService recovered = new AnalysisService(new RevisionStore(dir));
        RevisionView resubmittedAfterRestart =
                recovered.update(Map.of("Main.java", MAIN_V2));
        assertEquals(2L, resubmittedAfterRestart.revision(),
                "a change already committed must not be re-applied after restart");
        assertEquals(historyAfterFirst, recovered.history().size(),
                "recovery + resubmission must not duplicate history");
        assertEquals(first.snapshot().varPointsTo(),
                resubmittedAfterRestart.snapshot().varPointsTo());
    }

    @Test
    void crashAfterSnapshotBeforeMarkerAlignsToCommittedSnapshot(@TempDir Path dir)
            throws Exception {
        RevisionStore store = new RevisionStore(dir);
        AnalysisService service = new AnalysisService(store);
        service.build(Map.of("Main.java", MAIN_V1), AnalysisConfig.defaults());

        store.setFailureHook((point, revision) -> {
            if (point == RevisionStore.PublishPoint.AFTER_SNAPSHOT_BEFORE_MARKER
                    && revision == 2L) {
                throw new RevisionStore.InjectedCrash("simulated crash mid-publish");
            }
        });
        assertThrows(RevisionStore.InjectedCrash.class,
                () -> service.update(Map.of("Main.java", MAIN_V2)));

        // Snapshot committed, marker not yet rewritten.
        assertTrue(Files.exists(dir.resolve("snapshot.json")));

        RevisionStore recoveredStore = new RevisionStore(dir);
        long alignedRevision = recoveredStore.reconcileMarkerWithSnapshot();
        assertEquals(2L, alignedRevision);
        assertEquals(2L, recoveredStore.lastAppliedRevision());

        AnalysisService recovered = new AnalysisService(recoveredStore);
        assertEquals(2L, recovered.currentView().revision());
        // The committed v2 state answers exactly like a fresh full analysis.
        AnalysisService full = new AnalysisService(new RevisionStore(dir.resolve("full")));
        full.build(Map.of("Main.java", MAIN_V2), AnalysisConfig.defaults());
        assertEquals(full.currentView().snapshot().varPointsTo(),
                recovered.currentView().snapshot().varPointsTo());
        assertEquals(full.currentView().snapshot().fieldPointsTo(),
                recovered.currentView().snapshot().fieldPointsTo());

        // Query answers after recovery line up with the same full analysis.
        QueryRecord answer = recovered.queryPointsTo(
                com.github.guoyyit.pointeranalysisjava.model.Context.ROOT,
                com.github.guoyyit.pointeranalysisjava.model.AccessPath
                        .local("Main#main/0", "b"));
        QueryRecord expectedAnswer = full.queryPointsTo(
                com.github.guoyyit.pointeranalysisjava.model.Context.ROOT,
                com.github.guoyyit.pointeranalysisjava.model.AccessPath
                        .local("Main#main/0", "b"));
        assertEquals(expectedAnswer.answer(), answer.answer());
        assertEquals(2L, answer.revision());
        assertEquals(recovered.persistedRevision(),
                recovered.currentView().revision(),
                "marker, published snapshot and queries share one revision");

        // Retrying the same change after recovery is a no-op.
        RevisionView retried = recovered.update(Map.of("Main.java", MAIN_V2));
        assertEquals(2L, retried.revision());
    }

    @Test
    void crashBeforeSnapshotWriteKeepsPreviousConsistentState(@TempDir Path dir)
            throws Exception {
        RevisionStore store = new RevisionStore(dir);
        AnalysisService service = new AnalysisService(store);
        service.build(Map.of("Main.java", MAIN_V1), AnalysisConfig.defaults());
        List<QueryRecord> historyBefore = service.history();

        store.setFailureHook((point, revision) -> {
            if (point == RevisionStore.PublishPoint.BEFORE_SNAPSHOT_WRITE
                    && revision == 2L) {
                throw new RevisionStore.InjectedCrash("crash before durable commit");
            }
        });
        assertThrows(RevisionStore.InjectedCrash.class,
                () -> service.update(Map.of("Main.java", MAIN_V2)));

        AnalysisService recovered = new AnalysisService(new RevisionStore(dir));
        assertEquals(1L, recovered.currentView().revision(),
                "only the previous fully committed snapshot is visible");
        assertEquals(historyBefore.size(), recovered.history().size());
        assertEquals(fullAnalysis(dir, MAIN_V1).varPointsTo(),
                recovered.currentView().snapshot().varPointsTo());

        // The change can then be applied normally and reaches revision 2.
        RevisionView applied = recovered.update(Map.of("Main.java", MAIN_V2));
        assertEquals(2L, applied.revision());
    }

    private com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot
            fullAnalysis(Path dir, String sources) {
        AnalysisService full =
                new AnalysisService(new RevisionStore(dir.resolve("full-" + System.nanoTime())));
        full.build(Map.of("Main.java", sources), AnalysisConfig.defaults());
        return full.currentView().snapshot();
    }
}
