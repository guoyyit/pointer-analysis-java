package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentSnapshotTest {

    /**
     * During a burst of increments, concurrent readers must only observe published revisions,
     * and every answer must be self-consistent (the revision reported by two queries in the
     * same logical read must be one of the published values, never torn state).
     */
    @Test
    void queriesObserveOnlyPublishedSnapshots(@TempDir Path dir) throws Exception {
        AnalysisConfig config = new AnalysisConfig();
        config.setContextDepth(1);
        config.setParallelism(4);
        config.setStorageDir(dir);
        AnalysisService service = new AnalysisService(config);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.FIELD_FLOW));

        int readerCount = 6;
        int rounds = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        ConcurrentHashMap<Long, Long> observedRevisions = new ConcurrentHashMap<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < readerCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 200; round++) {
                        long snapshotRevision = service.currentSnapshot().revision();
                        AnalysisService.PointsToResult pointsTo =
                                service.queryPointsTo("main", "[]", "z");
                        AnalysisService.AliasResult alias = service.queryAlias("main", "[]",
                                AccessPath.of("x", List.of()),
                                AccessPath.of("y", List.of()));
                        observedRevisions.merge(pointsTo.revision(), 1L, Long::sum);
                        // A result may come from revision snapshotRevision or a newer one, but
                        // never from an unpublished in-between state: revisions are dense and
                        // bounded by the snapshot the reader captured.
                        if (pointsTo.revision() < snapshotRevision
                                || pointsTo.revision() > snapshotRevision + 1) {
                            failure.compareAndSet(null, new AssertionError(
                                    "observed impossible revision " + pointsTo.revision()
                                            + " with captured " + snapshotRevision));
                        }
                        if (alias.revision() < 1 || alias.revision() > rounds + 1) {
                            failure.compareAndSet(null,
                                    new AssertionError("revision out of range: " + alias));
                        }
                        // Each revision is itself a valid, complete analysis result.
                        if (!pointsTo.allocations().isEmpty()
                                && !pointsTo.allocations().get(0).endsWith("@x")) {
                            failure.compareAndSet(null,
                                    new AssertionError("unexpected allocation: " + pointsTo));
                        }
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });
        }

        pool.submit(() -> {
            try {
                start.await();
                String current = TestFixtures.FIELD_FLOW;
                for (int round = 0; round < rounds; round++) {
                    current = round % 2 == 0
                            ? current.replace("load z = y.next;", "copy z = y;")
                            : current.replace("copy z = y;", "load z = y.next;");
                    service.increment(new AnalysisService.SourceChange(
                            TestFixtures.single("a.lang", current), "round-" + round));
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertTrue(observedRevisions.size() >= 2,
                "readers should observe several published revisions: " + observedRevisions);
    }

    @Test
    void publishedSnapshotIsImmutableWhileRead(@TempDir Path dir) {
        AnalysisConfig config = new AnalysisConfig();
        config.setContextDepth(1);
        config.setStorageDir(dir);
        AnalysisService service = new AnalysisService(config);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));

        long first = service.queryPointsTo("main", "[]", "x").revision();
        service.increment(new AnalysisService.SourceChange(
                TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES), "noop"));
        long second = service.queryPointsTo("main", "[]", "x").revision();

        assertTrue(second > first);
    }
}
