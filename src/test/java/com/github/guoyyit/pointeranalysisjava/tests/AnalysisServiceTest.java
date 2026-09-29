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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisServiceTest {

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

    private AnalysisService newService(Path dir) {
        return new AnalysisService(new RevisionStore(dir));
    }

    @Test
    void queriesObserveConsistentSnapshotsUnderUpdates(@TempDir Path dir) throws Exception {
        AnalysisService service = newService(dir);
        service.build(Map.of("Main.java", MAIN_V1), AnalysisConfig.defaults());

        int readers = 8;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> observedAnswerSignatures = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();

        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    service.update(Map.of("Main.java", i % 2 == 0 ? MAIN_V2 : MAIN_V1));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));

        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
        RevisionView view = service.currentView();
        long revision = view.revision();
        int objects = view.snapshot().varPointsTo().values().stream()
                                .mapToInt(Set::size).sum();
                        boolean even = (revision - 1) % 2 == 0;
                        int expected = even ? 1 : 2;
                        if (objects != expected) {
                            throw new AssertionError("torn snapshot: revision " + revision
                                    + " objects " + objects);
                        }
                        observedAnswerSignatures.add(revision + ":" + objects);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(observedAnswerSignatures.size() >= 2,
                "readers observed both revisions, each consistently");
    }

    @Test
    void crashRecoveryKeepsSnapshotAndHistory(@TempDir Path dir) {
        AnalysisService service = newService(dir);
        service.build(Map.of("Main.java", MAIN_V1), AnalysisConfig.defaults());
        service.queryPointsTo(Context.ROOT, AccessPath.local("Main#main/0", "a"));
        service.queryAlias(Context.ROOT, AccessPath.local("Main#main/0", "a"),
                AccessPath.local("Main#main/0", "a"));
        long revisionBefore = service.currentView().revision();
        int historyBefore = service.history().size();

        AnalysisService recovered = newService(dir);
        assertTrue(recovered.isReady());
        assertEquals(revisionBefore, recovered.currentView().revision());
        assertEquals(historyBefore, recovered.history().size());
        assertFalse(recovered.currentView().snapshot().varPointsTo().isEmpty());
    }

    @Test
    void recoveredIncrementalMatchesFreshFullRebuild(@TempDir Path dir) {
        AnalysisService service = newService(dir);
        service.build(Map.of("Main.java", MAIN_V1), AnalysisConfig.defaults());

        AnalysisService recovered = newService(dir);
        recovered.update(Map.of("Main.java", MAIN_V2));

        AnalysisService full = newService(dir.resolve("full"));
        full.build(Map.of("Main.java", MAIN_V2), AnalysisConfig.defaults());

        assertEquals(full.currentView().snapshot().varPointsTo(),
                recovered.currentView().snapshot().varPointsTo());
        assertEquals(full.currentView().snapshot().fieldPointsTo(),
                recovered.currentView().snapshot().fieldPointsTo());
    }

    @Test
    void verificationReportsConsistency(@TempDir Path dir) {
        AnalysisService service = newService(dir);
        service.build(Map.of("Main.java", MAIN_V1), AnalysisConfig.defaults());
        AnalysisService.VerificationReport report =
                service.verifyIncremental(Map.of("Main.java", MAIN_V2));
        assertTrue(report.consistent(), report.mismatches().toString());
    }

    @Test
    void queryHistoryIsDeterministicAndReproducible(@TempDir Path dir) {
        AnalysisConfig config = new AnalysisConfig(2, 1, "Main", "main");
        List<QueryRecord> first;
        AnalysisService service = newService(dir);
        service.build(Map.of("Main.java", MAIN_V2), config);
        service.queryPointsTo(Context.ROOT, AccessPath.local("Main#main/0", "a"));
        service.queryPointsTo(Context.ROOT, AccessPath.local("Main#main/0", "b"));
        service.queryAlias(Context.ROOT, AccessPath.local("Main#main/0", "a"),
                AccessPath.local("Main#main/0", "b"));
        first = service.history();

        AnalysisService second = new AnalysisService(new RevisionStore(dir.resolve("other")));
        second.build(Map.of("Main.java", MAIN_V2), config);
        second.queryPointsTo(Context.ROOT, AccessPath.local("Main#main/0", "a"));
        second.queryPointsTo(Context.ROOT, AccessPath.local("Main#main/0", "b"));
        second.queryAlias(Context.ROOT, AccessPath.local("Main#main/0", "a"),
                AccessPath.local("Main#main/0", "b"));
        List<QueryRecord> again = second.history();

        assertEquals(first.size(), again.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).kind(), again.get(i).kind());
            assertEquals(first.get(i).answer(), again.get(i).answer());
        }
        Set<Long> sequences = new HashSet<>();
        again.forEach(record -> assertTrue(sequences.add(record.sequence()),
                "history sequence numbers are unique"));
    }
}
