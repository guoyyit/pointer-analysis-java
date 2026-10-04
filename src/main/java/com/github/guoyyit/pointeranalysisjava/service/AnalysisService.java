package com.github.guoyyit.pointeranalysisjava.service;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.analysis.ChangeSet;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSolver;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.Context;
import com.github.guoyyit.pointeranalysisjava.model.Obj;
import com.github.guoyyit.pointeranalysisjava.parser.SourceParser;
import com.github.guoyyit.pointeranalysisjava.persistence.QueryRecord;
import com.github.guoyyit.pointeranalysisjava.persistence.RevisionStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Entry point of the analysis service.
 *
 * <p>Concurrency model: the currently published state is an immutable
 * {@link RevisionView} held in an {@link AtomicReference}. Queries read that
 * reference once and therefore always observe one consistent published
 * snapshot, never a mix of old and new facts. Analysis/updates are serialized
 * by a single writer lock; they mutate a scratch solver and only swap the
 * reference once a new immutable snapshot has been fully computed and persisted.
 *
 * <p>Determinism: parsing and solving use total orderings everywhere, so the
 * snapshot for a given (sources, config, operation sequence) is identical no
 * matter how updates interleave with queries.
 */
public final class AnalysisService {

    private final RevisionStore store;
    private final AtomicReference<RevisionView> published = new AtomicReference<>();
    private final ReentrantLock writerLock = new ReentrantLock();

    private AnalysisConfig config = AnalysisConfig.defaults();
    private Program currentProgram;
    private PointerAnalysisSolver solver;
    private String lastAppliedKey;

    public AnalysisService(RevisionStore store) {
        this.store = Objects.requireNonNull(store);
        recover();
    }

    private void recover() {
        try {
            PointerAnalysisSnapshot snapshot = store.loadLatestSnapshot();
            if (snapshot != null) {
                this.config = snapshot.config();
                this.currentProgram = snapshot.program();
                this.solver = null;
                this.published.set(new RevisionView(snapshot.revision(), snapshot,
                        List.copyOf(store.queryHistory())));
            }
            RevisionStore.PendingChange pending = store.loadPendingChange();
            if (pending != null) {
                if (snapshot != null && snapshot.revision() == pending.targetRevision()) {
                    // The update committed (snapshot write succeeded); remember its key.
                    this.lastAppliedKey = pending.changeKey();
                }
                // else: the update did not commit; discard the stale marker.
                store.clearPendingChange();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("recovery failed", e);
        }
    }

    public RevisionView currentView() {
        RevisionView view = published.get();
        if (view == null) {
            throw new IllegalStateException("no analysis has been published yet");
        }
        return view;
    }

    public boolean isReady() {
        return published.get() != null;
    }

    public RevisionView build(Map<String, String> pathToContent, AnalysisConfig requested) {
        writerLock.lock();
        try {
            this.config = requested != null ? requested : AnalysisConfig.defaults();
            Program program = SourceParser.parse(pathToContent, config.parallelism());
            this.solver = new PointerAnalysisSolver(this.config);
            PointerAnalysisSnapshot snapshot = solver.solve(program);
            return commit(snapshot, program, null);
        } finally {
            writerLock.unlock();
        }
    }

    public RevisionView update(Map<String, String> fullSourcesAfterChange) {
        writerLock.lock();
        try {
            requireReady();
            RevisionView previous = published.get();
            Program nextProgram = SourceParser.parse(fullSourcesAfterChange, config.parallelism());
            ChangeSet changeSet = ChangeSet.diff(currentProgram, nextProgram);
            if (changeSet.isEmpty()) {
                return previous;
            }
            String changeKey = changeSet.fingerprint(previous.revision(), nextProgram);
            if (changeKey.equals(lastAppliedKey)) {
                return previous;
            }
            long targetRevision = previous.revision() + 1;
            try {
                store.writePendingChange(changeKey, targetRevision);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to write pending change marker", e);
            }
            PointerAnalysisSnapshot next;
            try {
                if (solver != null) {
                    next = solver.update(currentProgram, previous.snapshot(), nextProgram,
                            changeSet);
                } else {
                    PointerAnalysisSolver fresh = new PointerAnalysisSolver(config);
                    fresh.solve(currentProgram);
                    next = fresh.update(currentProgram, previous.snapshot(), nextProgram,
                            changeSet);
                    this.solver = fresh;
                }
            } catch (RuntimeException e) {
                store.clearPendingChange();
                throw e;
            }
            return commit(next, nextProgram, changeKey);
        } finally {
            writerLock.unlock();
        }
    }

    public RevisionView rebuild(Map<String, String> fullSourcesAfterChange) {
        writerLock.lock();
        try {
            requireReady();
            Program nextProgram = SourceParser.parse(fullSourcesAfterChange, config.parallelism());
            this.solver = new PointerAnalysisSolver(config);
            PointerAnalysisSnapshot next = solver.solve(nextProgram);
            return commit(next, nextProgram, null);
        } finally {
            writerLock.unlock();
        }
    }

    public VerificationReport verifyIncremental(Map<String, String> fullSourcesAfterChange) {
        RevisionView before;
        Program programBefore;
        AnalysisConfig snapshotConfig;
        writerLock.lock();
        try {
            before = currentView();
            programBefore = currentProgram;
            snapshotConfig = config;
        } finally {
            writerLock.unlock();
        }
        Program nextProgram = SourceParser.parse(fullSourcesAfterChange, snapshotConfig.parallelism());
        ChangeSet changeSet = ChangeSet.diff(programBefore, nextProgram);

        PointerAnalysisSolver incrementalSolver = new PointerAnalysisSolver(snapshotConfig);
        incrementalSolver.solve(programBefore);
        PointerAnalysisSnapshot incremental = incrementalSolver.update(
                programBefore, before.snapshot(), nextProgram, changeSet);

        PointerAnalysisSnapshot full =
                new PointerAnalysisSolver(snapshotConfig).solve(nextProgram);
        List<String> mismatches = new ArrayList<>();
        if (!full.varPointsTo().equals(incremental.varPointsTo())) {
            mismatches.add(describeVarDifference(full, incremental));
        }
        if (!full.fieldPointsTo().equals(incremental.fieldPointsTo())) {
            mismatches.add("field points-to sets differ");
        }
        return new VerificationReport(mismatches.isEmpty(), before.revision(),
                full.revision(), List.copyOf(mismatches));
    }

    private String describeVarDifference(PointerAnalysisSnapshot full,
                                         PointerAnalysisSnapshot incremental) {
        TreeMap<com.github.guoyyit.pointeranalysisjava.model.Var, TreeSet<Obj>> onlyFull =
                new TreeMap<>();
        full.varPointsTo().forEach((key, value) -> onlyFull.put(key, new TreeSet<>(value)));
        onlyFull.forEach((key, value) ->
                value.removeAll(incremental.varPointsTo().getOrDefault(key, new TreeSet<>())));
        onlyFull.values().removeIf(TreeSet::isEmpty);
        TreeMap<com.github.guoyyit.pointeranalysisjava.model.Var,
                TreeSet<Obj>> onlyIncremental = new TreeMap<>();
        incremental.varPointsTo().forEach((key, value) -> {
            TreeSet<Obj> extra = new TreeSet<>(value);
            extra.removeAll(full.varPointsTo().getOrDefault(key, new TreeSet<>()));
            if (!extra.isEmpty()) {
                onlyIncremental.put(key, extra);
            }
        });
        return "variable points-to mismatch: onlyFull=" + onlyFull
                + " onlyIncremental=" + onlyIncremental;
    }

    private RevisionView commit(PointerAnalysisSnapshot snapshot, Program program,
                                String changeKey) {
        try {
            store.saveSnapshot(snapshot);
            RevisionView view = new RevisionView(snapshot.revision(), snapshot,
                    List.copyOf(store.queryHistory()));
            published.set(view);
            store.clearPendingChange();
            this.currentProgram = program;
            this.lastAppliedKey = changeKey;
            return view;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to publish snapshot", e);
        }
    }

    private void requireReady() {
        if (published.get() == null) {
            throw new IllegalStateException("call build() before updates or queries");
        }
    }

    public QueryRecord queryPointsTo(Context context, AccessPath path) {
        RevisionView view = currentView();
        List<PointerAnalysisSnapshot.PathTarget> targets =
                view.snapshot().evaluatePath(context, path);
        String answer = targets.stream()
                .map(target -> target.context() + ":" + target.path() + "->"
                        + new TreeSet<>(target.targets()))
                .collect(Collectors.joining(" | "));
        return record(new QueryRecord(0, view.revision(), "points-to",
                context == null ? "*" : context.toString(), path.toString(), null, answer));
    }

    public QueryRecord queryAlias(Context context, AccessPath left, AccessPath right) {
        RevisionView view = currentView();
        boolean alias = view.snapshot().mayAlias(context, left, right);
        return record(new QueryRecord(0, view.revision(), "alias",
                context == null ? "*" : context.toString(), left.toString(), right.toString(),
                Boolean.toString(alias)));
    }

    private QueryRecord record(QueryRecord record) {
        try {
            QueryRecord stored = store.appendQuery(record);
            RevisionView old = published.get();
            published.set(new RevisionView(old.revision(), old.snapshot(),
                    List.copyOf(store.queryHistory())));
            return stored;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist query", e);
        }
    }

    public List<QueryRecord> history() {
        try {
            return List.copyOf(store.queryHistory());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public AnalysisConfig config() {
        return config;
    }

    /** Result of cross-checking incremental state against a full rebuild. */
    public record VerificationReport(boolean consistent, long incrementalRevision,
                                     long fullRevision, List<String> mismatches) {
    }
}
