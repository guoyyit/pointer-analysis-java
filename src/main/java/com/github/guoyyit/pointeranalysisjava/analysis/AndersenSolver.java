package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.model.Allocation;
import com.github.guoyyit.pointeranalysisjava.model.CVar;
import com.github.guoyyit.pointeranalysisjava.model.OField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Andersen-style inclusion-based pointer analysis solver with a deterministic fixpoint.
 *
 * <p>The constraint graph is indexed in both directions so a growing variable or object
 * field immediately drives every constraint reading it. The lattice is monotone and the
 * processing rules are the exact least-fixpoint closure of the Andersen constraints, so the
 * reached state is unique for a given constraint set and reproducible regardless of thread
 * interleaving.</p>
 *
 * <p>For incremental updates, callers provide a small set of seeds (variables of changed
 * methods and the endpoints of cross-boundary copy constraints). The solver computes the
 * dependency-reachable region, resets it to the {@code new} baseline and re-derives it. In
 * addition, caller-side arguments flowing into changed callees are registered as
 * "tainted sources": their (old) facts are replayed into the region so changed parameters
 * are updated even when the caller file itself is untouched.</p>
 */
public final class AndersenSolver {

    private final List<Constraint> constraints;
    private final PointsToState state;
    private final int parallelism;

    private final ConcurrentMap<CVar, List<Constraint.Copy>> copiesByTarget =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<CVar, List<Constraint.Copy>> copiesBySource =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<CVar, List<Constraint.Store>> storesByBase =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<CVar, List<Constraint.Store>> storesByValue =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<CVar, List<Constraint.Load>> loadsByBase =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<CVar, List<Constraint.Load>> loadsByTarget =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Constraint.Load>> loadsByField =
            new ConcurrentHashMap<>();

    private final AtomicInteger processedVariables = new AtomicInteger(0);
    private volatile int affectedVariableCount = 0;

    public AndersenSolver(List<Constraint> constraints, PointsToState state, int parallelism) {
        this.constraints = List.copyOf(constraints);
        this.state = state;
        this.parallelism = Math.max(1, parallelism);
        for (Constraint constraint : this.constraints) {
            if (constraint instanceof Constraint.NewC allocation) {
                state.local(allocation.lhs()).add(allocation.object());
            } else if (constraint instanceof Constraint.Copy copy) {
                copiesByTarget.computeIfAbsent(copy.lhs(), ignored -> new ArrayList<>()).add(copy);
                copiesBySource.computeIfAbsent(copy.rhs(), ignored -> new ArrayList<>()).add(copy);
                state.local(copy.lhs());
                state.local(copy.rhs());
            } else if (constraint instanceof Constraint.Store store) {
                storesByBase.computeIfAbsent(store.base(), ignored -> new ArrayList<>())
                        .add(store);
                storesByValue.computeIfAbsent(store.rhs(), ignored -> new ArrayList<>())
                        .add(store);
                loadsByField.computeIfAbsent(store.field(), ignored -> new ArrayList<>());
                state.local(store.base());
                state.local(store.rhs());
            } else if (constraint instanceof Constraint.Load load) {
                loadsByBase.computeIfAbsent(load.base(), ignored -> new ArrayList<>()).add(load);
                loadsByTarget.computeIfAbsent(load.lhs(), ignored -> new ArrayList<>())
                        .add(load);
                loadsByField.computeIfAbsent(load.field(), ignored -> new ArrayList<>())
                        .add(load);
                state.local(load.base());
                state.local(load.lhs());
            }
        }
    }

    public PointsToState solve() {
        return runFixpoint(new ArrayList<>(new java.util.TreeSet<>(state.localSets().keySet())));
    }

    /**
     * Re-solves the dependency-reachable region of the seeds.
     *
     * @param taintedSources variables outside the region whose current facts must be replayed
     *                       into the region (e.g. unchanged caller arguments feeding a
     *                       changed callee parameter)
     */
    public PointsToState update(List<CVar> affectedSeeds, List<CVar> taintedSources,
                                java.util.Collection<OField> staleFields) {
        List<CVar> seeds = new ArrayList<>(new java.util.TreeSet<>(affectedSeeds));
        java.util.SortedSet<CVar> tainted = new java.util.TreeSet<>(taintedSources);
        java.util.SortedSet<OField> affectedFields = new java.util.TreeSet<>(staleFields);
        collectAffectedFields(seeds, tainted, affectedFields);
        List<CVar> dirty = markDirty(seeds, tainted, affectedFields);
        affectedVariableCount = dirty.size();
        java.util.SortedSet<CVar> dirtySet = new java.util.TreeSet<>(dirty);

        // Also collect fields written by *old* stores that the new constraints no longer know
        // about, using previous base sets: any field previously populated through a store
        // touching the region must be cleared and re-derived. This is done before locals reset.
        collectAffectedFields(dirtySet, tainted, affectedFields);
        List<CVar> dirtyExpanded = markDirty(seeds, tainted, affectedFields);
        if (!dirtyExpanded.equals(dirty)) {
            dirty = dirtyExpanded;
            dirtySet = new java.util.TreeSet<>(dirty);
            collectAffectedFields(dirtySet, tainted, affectedFields);
        }

        for (CVar variable : dirty) {
            synchronized (state.local(variable)) {
                state.local(variable).clear();
            }
            for (Constraint.NewC seedConstraint : newConstraints(variable)) {
                addAll(state.local(variable), List.of(seedConstraint.object()));
            }
        }
        for (OField objectField : affectedFields) {
            synchronized (state.field(objectField)) {
                state.field(objectField).clear();
            }
        }
        // Replay tainted source facts: union every outside source into its in-region target.
        for (CVar source : tainted) {
            for (Constraint.Copy copy : copiesBySource.getOrDefault(source, List.of())) {
                if (dirtySet.contains(copy.lhs())) {
                    addAll(state.local(copy.lhs()), state.local(source));
                }
            }
        }
        // Unchanged writers still feeding affected fields must re-pour their retained values.
        java.util.SortedSet<CVar> replay = new java.util.TreeSet<>();
        for (Constraint constraint : constraints) {
            if (!(constraint instanceof Constraint.Store store)) {
                continue;
            }
            for (Allocation allocation : List.copyOf(state.local(store.base()))) {
                if (affectedFields.contains(new OField(allocation, store.field()))) {
                    replay.add(store.rhs());
                    replay.add(store.base());
                }
            }
        }
        replay.removeAll(dirtySet);
        List<CVar> initial = new ArrayList<>(dirty);
        initial.addAll(replay);
        return runFixpoint(initial);
    }

    private void collectAffectedFields(java.util.Collection<CVar> region,
                                       java.util.Collection<CVar> tainted,
                                       java.util.SortedSet<OField> affectedFields) {
        java.util.Set<CVar> relevant = new java.util.HashSet<>(region);
        relevant.addAll(tainted);
        for (CVar variable : relevant) {
            for (Constraint.Store store : storesByBase.getOrDefault(variable, List.of())) {
                for (Allocation allocation : List.copyOf(state.local(variable))) {
                    affectedFields.add(new OField(allocation, store.field()));
                }
            }
            for (Constraint.Store store : storesByValue.getOrDefault(variable, List.of())) {
                for (Allocation allocation : List.copyOf(state.local(store.base()))) {
                    affectedFields.add(new OField(allocation, store.field()));
                }
            }
        }
    }

    public int processedVariables() {
        return processedVariables.get();
    }

    public int affectedVariableCount() {
        return affectedVariableCount;
    }

    private List<Constraint.NewC> newConstraints(CVar variable) {
        List<Constraint.NewC> result = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (constraint instanceof Constraint.NewC allocation
                    && allocation.lhs().equals(variable)) {
                result.add(allocation);
            }
        }
        return result;
    }

    private List<CVar> markDirty(List<CVar> seeds, java.util.SortedSet<CVar> tainted,
                                 java.util.Set<OField> extraFields) {
        java.util.SortedSet<CVar> dirty = new java.util.TreeSet<>(seeds);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (CVar seed : new ArrayList<>(dirty)) {
                for (Constraint.Copy copy : copiesBySource.getOrDefault(seed, List.of())) {
                    grew |= dirty.add(copy.lhs());
                }
                for (Constraint.Load load : loadsByBase.getOrDefault(seed, List.of())) {
                    grew |= dirty.add(load.lhs());
                }
            }
            java.util.SortedSet<OField> touched = new java.util.TreeSet<>();
            touched.addAll(extraFields);
            for (CVar variable : dirty) {
                for (Constraint.Store store : storesByBase.getOrDefault(variable, List.of())) {
                    for (Allocation allocation : state.local(variable)) {
                        touched.add(new OField(allocation, store.field()));
                    }
                }
                for (Constraint.Store store : storesByValue.getOrDefault(variable, List.of())) {
                    for (Allocation allocation : state.local(store.base())) {
                        touched.add(new OField(allocation, store.field()));
                    }
                }
            }
            for (OField objectField : touched) {
                for (Constraint.Load load : loadsByField.getOrDefault(
                        objectField.field(), List.of())) {
                    if (dirty.contains(load.base())
                            || state.local(load.base()).contains(objectField.allocation())) {
                        grew |= dirty.add(load.lhs());
                    }
                }
            }
        }
        return new ArrayList<>(dirty);
    }

    private PointsToState runFixpoint(List<CVar> seeds) {
        LinkedBlockingQueue<CVar> worklist = new LinkedBlockingQueue<>(seeds);
        AtomicInteger active = new AtomicInteger(0);
        int workerCount = Math.min(parallelism, Math.max(1, seeds.size()));
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            Thread worker = new Thread(() -> drain(worklist, active), "andersen-worker");
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Pointer analysis interrupted", exception);
            }
        }
        state.normalize();
        return state;
    }

    private void drain(LinkedBlockingQueue<CVar> worklist, AtomicInteger active) {
        while (true) {
            CVar variable = worklist.poll();
            if (variable == null) {
                if (active.get() == 0) {
                    return;
                }
                continue;
            }
            active.incrementAndGet();
            try {
                processedVariables.incrementAndGet();
                process(variable, worklist);
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private void process(CVar variable, LinkedBlockingQueue<CVar> worklist) {
        SortedSet<Allocation> pointsTo = state.local(variable);
        for (Constraint.Copy copy : copiesByTarget.getOrDefault(variable, List.of())) {
            if (addAll(state.local(variable), state.local(copy.rhs()))) {
                worklist.add(variable);
            }
        }
        for (Constraint.Load load : loadsByTarget.getOrDefault(variable, List.of())) {
            for (Allocation allocation : List.copyOf(state.local(load.base()))) {
                SortedSet<Allocation> fieldSet =
                        state.field(new OField(allocation, load.field()));
                synchronized (fieldSet) {
                    if (addAll(state.local(variable), fieldSet)) {
                        worklist.add(variable);
                    }
                }
            }
        }
        for (Constraint.Copy copy : copiesBySource.getOrDefault(variable, List.of())) {
            if (addAll(state.local(copy.lhs()), pointsTo)) {
                worklist.add(copy.lhs());
            }
        }
        for (Constraint.Store store : storesByValue.getOrDefault(variable, List.of())) {
            for (Allocation allocation : List.copyOf(state.local(store.base()))) {
                writeField(allocation, store.field(), variable, worklist);
            }
        }
        for (Constraint.Store store : storesByBase.getOrDefault(variable, List.of())) {
            for (Allocation allocation : List.copyOf(pointsTo)) {
                writeField(allocation, store.field(), store.rhs(), worklist);
            }
        }
        for (Constraint.Load load : loadsByBase.getOrDefault(variable, List.of())) {
            for (Allocation allocation : List.copyOf(pointsTo)) {
                SortedSet<Allocation> fieldSet =
                        state.field(new OField(allocation, load.field()));
                synchronized (fieldSet) {
                    if (addAll(state.local(load.lhs()), fieldSet)) {
                        worklist.add(load.lhs());
                    }
                }
            }
        }
    }

    private void writeField(Allocation allocation, String field, CVar valueSource,
                            LinkedBlockingQueue<CVar> worklist) {
        OField objectField = new OField(allocation, field);
        SortedSet<Allocation> fieldSet = state.field(objectField);
        boolean changed;
        synchronized (fieldSet) {
            changed = fieldSet.addAll(List.copyOf(state.local(valueSource)));
        }
        if (!changed) {
            return;
        }
        for (Constraint.Load load : loadsByField.getOrDefault(field, List.of())) {
            if (state.local(load.base()).contains(allocation)) {
                SortedSet<Allocation> targetSet = state.local(load.lhs());
                synchronized (fieldSet) {
                    if (addAll(targetSet, fieldSet)) {
                        worklist.add(load.lhs());
                    }
                }
            }
        }
    }

    private boolean addAll(SortedSet<Allocation> target, Collection<Allocation> additions) {
        if (additions.isEmpty()) {
            return false;
        }
        synchronized (target) {
            return target.addAll(additions);
        }
    }
}
