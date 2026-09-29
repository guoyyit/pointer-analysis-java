package com.github.guoyyit.pointeranalysisjava.service;

import com.github.guoyyit.pointeranalysisjava.analysis.FullAnalysis;
import com.github.guoyyit.pointeranalysisjava.analysis.IncrementalAnalysis;
import com.github.guoyyit.pointeranalysisjava.analysis.QueryEngine;
import com.github.guoyyit.pointeranalysisjava.ast.Program;
import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.Allocation;
import com.github.guoyyit.pointeranalysisjava.model.CVar;
import com.github.guoyyit.pointeranalysisjava.model.Ctx;
import com.github.guoyyit.pointeranalysisjava.model.SourceFile;
import com.github.guoyyit.pointeranalysisjava.parser.SourceParser;
import com.github.guoyyit.pointeranalysisjava.snapshot.Snapshot;
import com.github.guoyyit.pointeranalysisjava.snapshot.SnapshotStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Application service coordinating parsing, analysis, immutable snapshots, concurrency and
 * crash recovery.
 *
 * <p>Analyses and increments are serialized by an intrinsic lock, which makes the sequence of
 * published revisions deterministic for a given request order; queries never take the lock and
 * always run against the snapshot reference captured at call entry. The reference is replaced
 * atomically after the successor state is fully built and durably persisted, so readers never
 * observe mixed old/new state.</p>
 */
@org.springframework.stereotype.Service
public class AnalysisService {

    private final AnalysisConfig config;
    private final SourceParser parser = new SourceParser();
    private final QueryEngine queryEngine = new QueryEngine();
    private final SnapshotStore store;
    private final AtomicReference<Snapshot> published = new AtomicReference<>();
    private final Object analysisLock = new Object();

    public AnalysisService(AnalysisConfig config) {
        this.config = config;
        this.store = new SnapshotStore(config.getStorageDir());
        Snapshot restored = store.loadLatest();
        published.set(restored != null ? restored : Snapshot.empty(config.getContextDepth()));
    }

    public void rebuild(List<SourceFile> files) {
        synchronized (analysisLock) {
            Program program = parser.parse(files);
            SortedMap<String, String> sources = indexSources(files);
            Snapshot snapshot = new FullAnalysis(config).analyze(program, sources,
                    new TreeSet<>(), 0L);
            publish(snapshot);
        }
    }

    public void increment(SourceChange change) {
        synchronized (analysisLock) {
            publish(computeIncrement(change).snapshot());
        }
    }

    public com.github.guoyyit.pointeranalysisjava.analysis.IncrementalAnalysis.UpdateResult
            incrementWithStats(SourceChange change) {
        synchronized (analysisLock) {
            com.github.guoyyit.pointeranalysisjava.analysis.IncrementalAnalysis.UpdateResult
                    result = computeIncrement(change);
            publish(result.snapshot());
            return result;
        }
    }

    private com.github.guoyyit.pointeranalysisjava.analysis.IncrementalAnalysis.UpdateResult
            computeIncrement(SourceChange change) {
        Snapshot current = published.get();
            if (change.changeId() != null && current.appliedChanges().contains(change.changeId())) {
                return new com.github.guoyyit.pointeranalysisjava.analysis
                        .IncrementalAnalysis.UpdateResult(current, 0, 0, 0);
            }
            TreeMap<String, String> merged = new TreeMap<>(current.sources());
            Set<String> changedPaths = new TreeSet<>();
            for (SourceFile file : change.files()) {
                merged.put(file.path(), file.content());
                changedPaths.add(file.path());
            }
            Program oldProgram = parser.parse(toFiles(current.sources()));
            Program newProgram = parser.parse(toFiles(merged));
            TreeSet<String> applied = new TreeSet<>(current.appliedChanges());
            if (change.changeId() != null) {
                applied.add(change.changeId());
            }
            return new IncrementalAnalysis(config)
                    .updateWithStats(current, oldProgram, newProgram, changedPaths, merged,
                            applied);
        }

    public Snapshot currentSnapshot() {
        return published.get();
    }

    public PointsToResult queryPointsTo(String method, String context, String expression) {
        Snapshot snapshot = published.get();
        Ctx ctx = queryEngine.parseContext(context);
        CVar root = queryEngine.resolveRoot(snapshot, method, ctx,
                queryEngine.parseExpression(expression, expression).base());
        SortedSet<Allocation> allocations = root == null
                ? new TreeSet<>()
                : queryEngine.resolveExpression(snapshot, root, expression);
        PointsToResult result = new PointsToResult(
                snapshot.revision(), method, queryEngine.formatContext(ctx), expression,
                allocations.stream().map(Allocation::siteId).toList());
        recordQuery("points-to", method + "|" + context + "|" + expression,
                String.join(",", result.allocations()));
        return result;
    }

    public AliasResult queryAlias(String method, String context, AccessPath a, AccessPath b) {
        Snapshot snapshot = published.get();
        Ctx ctx = queryEngine.parseContext(context);
        CVar rootA = queryEngine.resolveRoot(snapshot, method, ctx, a.base());
        CVar rootB = queryEngine.resolveRoot(snapshot, method, ctx, b.base());
        boolean mayAlias = rootA != null && rootB != null
                && queryEngine.mayAlias(snapshot, rootA, a, rootB, b);
        AliasResult result = new AliasResult(snapshot.revision(),
                queryEngine.formatContext(ctx), a, b, mayAlias);
        recordQuery("alias", method + "|" + context + "|" + a + "|" + b,
                Boolean.toString(mayAlias));
        return result;
    }

    public VerificationResult verifyAgainstFullRebuild() {
        synchronized (analysisLock) {
            Snapshot current = published.get();
            Program program = parser.parse(toFiles(current.sources()));
            Snapshot reference = new FullAnalysis(config).analyze(program, current.sources(),
                    new TreeSet<>(current.appliedChanges()), current.revision() - 1);
            List<String> mismatches = new ArrayList<>();
            compareLocals(current, reference, mismatches);
            compareFields(current, reference, mismatches);
            return new VerificationResult(mismatches.isEmpty(), mismatches);
        }
    }

    public List<SnapshotStore.QueryRecord> queryHistory() {
        return store.queryHistory();
    }

    SnapshotStore storeForRecovery() {
        return store;
    }

    private void publish(Snapshot snapshot) {
        store.persist(snapshot);
        published.set(snapshot);
    }

    private void recordQuery(String kind, String request, String result) {
        long sequence = store.nextQuerySequence();
        store.appendQuery(new SnapshotStore.QueryRecord(
                sequence, published.get().revision(), kind, request, result));
    }

    private void compareLocals(Snapshot actual, Snapshot expected, List<String> mismatches) {
        Set<CVar> keys = new TreeSet<>();
        keys.addAll(actual.localPointsTo().keySet());
        keys.addAll(expected.localPointsTo().keySet());
        for (CVar key : keys) {
            SortedSet<Allocation> actualSet = actual.localPointsTo().getOrDefault(key,
                    new TreeSet<>());
            SortedSet<Allocation> expectedSet = expected.localPointsTo().getOrDefault(key,
                    new TreeSet<>());
            if (!actualSet.equals(expectedSet)) {
                mismatches.add("local " + key + " incremental=" + actualSet
                        + " full=" + expectedSet);
            }
        }
    }

    private void compareFields(Snapshot actual, Snapshot expected, List<String> mismatches) {
        Set<com.github.guoyyit.pointeranalysisjava.model.OField> keys = new TreeSet<>();
        keys.addAll(actual.fieldPointsTo().keySet());
        keys.addAll(expected.fieldPointsTo().keySet());
        for (com.github.guoyyit.pointeranalysisjava.model.OField key : keys) {
            SortedSet<Allocation> actualSet = actual.fieldPointsTo().getOrDefault(key,
                    new TreeSet<>());
            SortedSet<Allocation> expectedSet = expected.fieldPointsTo().getOrDefault(key,
                    new TreeSet<>());
            if (!actualSet.equals(expectedSet)) {
                mismatches.add("field " + key + " incremental=" + actualSet
                        + " full=" + expectedSet);
            }
        }
    }

    private SortedMap<String, String> indexSources(List<SourceFile> files) {
        TreeMap<String, String> sources = new TreeMap<>();
        files.forEach(file -> sources.put(file.path(), file.content()));
        return sources;
    }

    private List<SourceFile> toFiles(SortedMap<String, String> sources) {
        return sources.entrySet().stream()
                .map(entry -> new SourceFile(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    public record SourceChange(List<SourceFile> files, String changeId) {
    }

    public record PointsToResult(long revision, String method, String context, String expression,
                                 List<String> allocations) {
    }

    public record AliasResult(long revision, String context, AccessPath a, AccessPath b,
                              boolean mayAlias) {
    }

    public record VerificationResult(boolean consistent, List<String> mismatches) {
    }
}
