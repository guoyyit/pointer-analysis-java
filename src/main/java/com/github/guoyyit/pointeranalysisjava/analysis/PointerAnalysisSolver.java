package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.ir.JClass;
import com.github.guoyyit.pointeranalysisjava.ir.JMethod;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.ir.Stmt;
import com.github.guoyyit.pointeranalysisjava.model.Context;
import com.github.guoyyit.pointeranalysisjava.model.InstanceFieldId;
import com.github.guoyyit.pointeranalysisjava.model.Obj;
import com.github.guoyyit.pointeranalysisjava.model.Var;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Andersen-style inclusion-based pointer analysis with k-call-string context
 * sensitivity and field sensitivity.
 *
 * <p>The fixpoint is maintained as a provenance graph of derived nodes.
 * Every node (points-to fact, reachability fact, call binding, structural
 * copy/load/store edge, statement activation) is present only while it has at
 * least one supporting {@link Deriv derivation}. A derivation lists its
 * prerequisite nodes and the originating statement site (its root), plus a
 * synthetic {@link #ENTRY_ROOT} for the entry method.
 *
 * <p>Incremental updates are exact: deleting or changing a file removes every
 * derivation rooted at a statement site of that file; nodes losing all
 * supports cascade away. New statements are then activated and propagation
 * runs to a fixpoint. A fact introduced independently by multiple statements
 * survives as long as any supporting derivation remains, so the result equals
 * a full rebuild.
 *
 * <p>All sets are sorted ({@link Comparable} nodes) and worklists are drained
 * in sorted order, giving deterministic, reproducible results for identical
 * inputs regardless of how the service interleaves updates and queries.
 */
public final class PointerAnalysisSolver {

    static final String ENTRY_ROOT = "<entry>";

    private final AnalysisConfig config;

    public PointerAnalysisSolver(AnalysisConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public PointerAnalysisSnapshot solve(Program program) {
        Solver solver = new Solver(config);
        engine = solver;
        solver.useProgram(program);
        return solver.runFromScratch(1L);
    }

    public PointerAnalysisSnapshot update(Program previousProgram,
                                          PointerAnalysisSnapshot previousSnapshot,
                                          Program nextProgram,
                                          ChangeSet changeSet) {
        Solver solver;
        if (engine != null && engine.matches(previousProgram)) {
            solver = engine;
        } else {
            solver = new Solver(config);
            solver.useProgram(previousProgram);
            solver.restoreFrom(previousSnapshot);
        }
        solver.useProgram(nextProgram);
        PointerAnalysisSnapshot snapshot =
                solver.incremental(previousProgram, changeSet, previousSnapshot.revision());
        this.lastIncrementalStats = solver.incrementalStats;
        engine = solver;
        return snapshot;
    }

    private Solver engine;

    /**
     * Deterministic, timing-independent work counters for the most recent
     * incremental update. Used to assert that re-analysis cost tracks the
     * affected region rather than total program size.
     *
     * @param affectedSites        statement sites belonging to changed files
     * @param derivationsRemoved   derivations torn down (including cascades)
     * @param derivationsRegistered derivations registered in the new run
     */
    public record IncrementalStats(int affectedSites, long derivationsRemoved,
                                   long derivationsRegistered) {
    }

    private IncrementalStats lastIncrementalStats;

    public IncrementalStats lastIncrementalStats() {
        return lastIncrementalStats;
    }

    /** A node in the provenance graph. */
    sealed interface Node extends Comparable<Node>
            permits VFact, FFact, ReachNode, ActiveNode, CopyNode, LoadNode, StoreNode,
                    CallNode {

        int ordinal();
    }

    record VFact(Var var, Obj obj) implements Node {
        @Override
        public int compareTo(Node other) {
            if (!(other instanceof VFact o)) {
                return ordinal() - other.ordinal();
            }
            int c = var.compareTo(o.var);
            return c != 0 ? c : obj.compareTo(o.obj);
        }

        @Override
        public int ordinal() {
            return 0;
        }
    }

    record FFact(InstanceFieldId field, Obj obj) implements Node {
        @Override
        public int compareTo(Node other) {
            if (!(other instanceof FFact o)) {
                return ordinal() - other.ordinal();
            }
            int c = field.compareTo(o.field);
            return c != 0 ? c : obj.compareTo(o.obj);
        }

        @Override
        public int ordinal() {
            return 1;
        }
    }

    record ReachNode(Context context, String method) implements Node {
        @Override
        public int compareTo(Node other) {
            if (!(other instanceof ReachNode o)) {
                return ordinal() - other.ordinal();
            }
            int c = context.compareTo(o.context);
            return c != 0 ? c : method.compareTo(o.method);
        }

        @Override
        public int ordinal() {
            return 2;
        }
    }

    record ActiveNode(String site, Context context, String method) implements Node {
        @Override
        public int compareTo(Node other) {
            if (!(other instanceof ActiveNode o)) {
                return ordinal() - other.ordinal();
            }
            int c = site.compareTo(o.site);
            if (c != 0) {
                return c;
            }
            c = context.compareTo(o.context);
            return c != 0 ? c : method.compareTo(o.method);
        }

        @Override
        public int ordinal() {
            return 3;
        }
    }

    record CopyNode(Var from, Var to, String rootSite) implements Node {
        @Override
        public int compareTo(Node other) {
            if (!(other instanceof CopyNode o)) {
                return ordinal() - other.ordinal();
            }
            int c = from.compareTo(o.from);
            if (c != 0) {
                return c;
            }
            c = to.compareTo(o.to);
            return c != 0 ? c : rootSite.compareTo(o.rootSite);
        }

        @Override
        public int ordinal() {
            return 4;
        }
    }

    record LoadNode(Var base, String field, Var to, String rootSite) implements Node {
        @Override
        public int compareTo(Node other) {
            if (!(other instanceof LoadNode o)) {
                return ordinal() - other.ordinal();
            }
            int c = base.compareTo(o.base);
            if (c != 0) {
                return c;
            }
            c = field.compareTo(o.field);
            if (c != 0) {
                return c;
            }
            c = to.compareTo(o.to);
            return c != 0 ? c : rootSite.compareTo(o.rootSite);
        }

        @Override
        public int ordinal() {
            return 5;
        }
    }

    record StoreNode(Var from, Var base, String field, String rootSite) implements Node {
        @Override
        public int compareTo(Node other) {
            if (!(other instanceof StoreNode o)) {
                return ordinal() - other.ordinal();
            }
            int c = from.compareTo(o.from);
            if (c != 0) {
                return c;
            }
            c = base.compareTo(o.base);
            if (c != 0) {
                return c;
            }
            c = field.compareTo(o.field);
            if (c != 0) {
                return c;
            }
            return rootSite.compareTo(o.rootSite);
        }

        @Override
        public int ordinal() {
            return 6;
        }
    }

    record CallNode(String site, Context callerContext, Context calleeContext, String target,
                    String rootSite) implements Node {
        @Override
        public int compareTo(Node other) {
            if (!(other instanceof CallNode o)) {
                return ordinal() - other.ordinal();
            }
            int c = site.compareTo(o.site);
            if (c != 0) {
                return c;
            }
            c = callerContext.compareTo(o.callerContext);
            if (c != 0) {
                return c;
            }
            c = calleeContext.compareTo(o.calleeContext);
            if (c != 0) {
                return c;
            }
            c = target.compareTo(o.target);
            return c != 0 ? c : rootSite.compareTo(o.rootSite);
        }

        @Override
        public int ordinal() {
            return 7;
        }
    }

    /**
     * A derivation: {@code produced} holds while every prerequisite is present.
     * Identity captures the full rule firing, so re-registration is idempotent.
     */
    record Deriv(Node produced, List<Node> prerequisites, String rootSite) {
        Deriv {
            List<Node> sorted = new ArrayList<>(prerequisites);
            Collections.sort(sorted);
            prerequisites = List.copyOf(sorted);
        }
    }

    private static final class Solver {

        private final AnalysisConfig config;
        private Program program;
        private final int k;

        private final Set<Node> present = new HashSet<>();
        private final Map<Deriv, Boolean> derivations = new HashMap<>();
        private final Map<Node, Set<Deriv>> supportsOf = new HashMap<>();
        private final Map<Node, Set<Deriv>> derivsUsing = new HashMap<>();
        private final Map<String, Set<Deriv>> derivsByRoot = new HashMap<>();
        private final Set<Node> queued = new HashSet<>();
        private final Deque<Node> worklist = new ArrayDeque<>();

        private final Map<Var, TreeSet<Obj>> varPts = new HashMap<>();
        private final Map<InstanceFieldId, TreeSet<Obj>> fieldPts = new HashMap<>();

        Solver(AnalysisConfig config, Program program) {
            this.config = config;
            this.k = config.contextK();
            this.program = program;
            this.matchedProgram = program;
        }

        private Program matchedProgram;

        Solver(AnalysisConfig config) {
            this.config = config;
            this.k = config.contextK();
        }

        void useProgram(Program program) {
            this.program = program;
            this.matchedProgram = program;
            buildProgramIndexes();
        }

        boolean matches(Program program) {
            return matchedProgram == program;
        }

        boolean hasState() {
            return !derivations.isEmpty();
        }

        void restoreFrom(PointerAnalysisSnapshot snapshot) {
            bootstrap(snapshot);
        }

        private String entryKey() {
            return config.entryClass() + "#" + config.entryMethod() + "/0";
        }

        private final Map<String, Stmt> stmtBySite = new HashMap<>();
        private final Map<String, JMethod> methodBySite = new HashMap<>();
        private final Map<String, String> allocTypeBySite = new HashMap<>();

        private void buildProgramIndexes() {
            stmtBySite.clear();
            methodBySite.clear();
            allocTypeBySite.clear();
            for (JMethod method : program.allMethods()) {
                for (Stmt stmt : method.body()) {
                    stmtBySite.put(stmt.site(), stmt);
                    methodBySite.put(stmt.site(), method);
                    if (stmt instanceof Stmt.New created) {
                        allocTypeBySite.put(created.allocSite(), created.type());
                    }
                }
            }
        }

        PointerAnalysisSnapshot runFromScratch(long revision) {
            registerEntryReach();
            drain();
            return materialize(revision);
        }

        private void registerEntryReach() {
            register(new ReachNode(Context.ROOT, entryKey()), List.of(), entryRootSite());
        }

        private String entryRootSite() {
            JMethod entry = program.method(entryKey());
            if (entry == null) {
                return ENTRY_ROOT;
            }
            if (!entry.body().isEmpty()) {
                return entry.body().get(0).site();
            }
            return fileOfMethod(entry).orElse(ENTRY_ROOT) + "#entry";
        }

        private java.util.Optional<String> fileOfMethod(JMethod target) {
            for (Map.Entry<String, List<String>> entry :
                    program.classesByFile().entrySet()) {
                if (entry.getValue().contains(target.className())) {
                    return java.util.Optional.of(entry.getKey());
                }
            }
            return java.util.Optional.empty();
        }

        void bootstrap(PointerAnalysisSnapshot snapshot) {
            snapshot.varPointsTo().forEach((var, pts) ->
                    varPts.put(var, new TreeSet<>(pts)));
            snapshot.fieldPointsTo().forEach((field, pts) ->
                    fieldPts.put(field, new TreeSet<>(pts)));
        }

        PointerAnalysisSnapshot incremental(Program oldProgram, ChangeSet changeSet,
                                            long previousRevision) {
            removalsSinceStart = 0L;
            registrationsSinceStart = 0L;
            int affectedSiteCount = 0;
            if (changeSet != null) {
                Set<String> affectedSites = new TreeSet<>();
                affectedSites.addAll(sitesOfFiles(oldProgram, changeSet.changedFiles()));
                affectedSites.addAll(sitesOfFiles(oldProgram, changeSet.deletedFiles()));
                affectedSites.addAll(sitesOfFiles(program, changeSet.addedFiles()));
                affectedSites.addAll(sitesOfFiles(program, changeSet.changedFiles()));
                affectedSiteCount = affectedSites.size();
                removeRootedAt(affectedSites);
            }
            registerEntryReach();
            drain();
            PointerAnalysisSnapshot snapshot = materialize(previousRevision + 1);
            incrementalStats = new IncrementalStats(affectedSiteCount,
                    removalsSinceStart, registrationsSinceStart);
            return snapshot;
        }

        private Set<String> sitesOfFiles(Program source, Set<String> files) {
            Set<String> sites = new HashSet<>();
            for (String file : files) {
                for (String className : source.classesByFile().getOrDefault(file, List.of())) {
                    JClass jClass = source.classNamed(className);
                    if (jClass == null) {
                        continue;
                    }
                    for (JMethod method : jClass.methods()) {
                        for (Stmt stmt : method.body()) {
                            sites.add(stmt.site());
                        }
                    }
                }
            }
            return sites;
        }

        private void removeRootedAt(Set<String> sites) {
            if (sites.isEmpty()) {
                return;
            }
            Set<Deriv> toRemove = new HashSet<>();
            for (String site : sites) {
                toRemove.addAll(derivsByRoot.getOrDefault(site, Set.of()));
            }
            if (toRemove.isEmpty()) {
                return;
            }
            Set<Node> reFireCandidates = new HashSet<>();
            Deque<Deriv> removalQueue = new ArrayDeque<>(toRemove);
            while (!removalQueue.isEmpty()) {
                Deriv deriv = removalQueue.pollFirst();
                if (!derivations.containsKey(deriv)) {
                    continue;
                }
                for (Node prerequisite : deriv.prerequisites()) {
                    if (present.contains(prerequisite)) {
                        reFireCandidates.add(prerequisite);
                    }
                }
                derivations.remove(deriv);
                removalsSinceStart++;
                Set<Deriv> sameRoot = derivsByRoot.get(deriv.rootSite());
                if (sameRoot != null) {
                    sameRoot.remove(deriv);
                    if (sameRoot.isEmpty()) {
                        derivsByRoot.remove(deriv.rootSite());
                    }
                }
                Node produced = deriv.produced();
                Set<Deriv> supports = supportsOf.get(produced);
                if (supports == null) {
                    continue;
                }
                supports.remove(deriv);
                if (supports.isEmpty()) {
                    supportsOf.remove(produced);
                    removeNode(produced);
                    for (Deriv downstream : new ArrayList<>(
                            derivsUsing.getOrDefault(produced, Set.of()))) {
                        removalQueue.add(downstream);
                    }
                }
            }
            for (Node producer : reFireCandidates) {
                if (present.contains(producer) && queued.add(producer)) {
                    worklist.add(producer);
                }
            }
        }

        private void removeNode(Node node) {
            present.remove(node);
            queued.remove(node);
            if (node instanceof VFact v) {
                TreeSet<Obj> pts = varPts.get(v.var());
                if (pts != null) {
                    pts.remove(v.obj());
                    if (pts.isEmpty()) {
                        varPts.remove(v.var());
                    }
                }
            } else if (node instanceof FFact f) {
                TreeSet<Obj> pts = fieldPts.get(f.field());
                if (pts != null) {
                    pts.remove(f.obj());
                    if (pts.isEmpty()) {
                        fieldPts.remove(f.field());
                    }
                }
            } else if (node instanceof CopyNode c) {
                copyIndex.getOrDefault(c.from(), new ArrayList<>()).remove(c);
            } else if (node instanceof LoadNode l) {
                loadIndex.getOrDefault(l.base(), new ArrayList<>()).remove(l);
                loadByFieldIndex.getOrDefault(l.field(), new ArrayList<>()).remove(l);
            } else if (node instanceof StoreNode s) {
                storeIndex.getOrDefault(s.from(), new ArrayList<>()).remove(s);
            }
        }

        private void register(Node produced, List<Node> prerequisites, String rootSite) {
            Deriv deriv = new Deriv(produced, prerequisites, rootSite);
            if (derivations.containsKey(deriv)) {
                return;
            }
            registrationsSinceStart++;
            derivations.put(deriv, Boolean.TRUE);
            supportsOf.computeIfAbsent(produced, ignored -> new HashSet<>()).add(deriv);
            derivsByRoot.computeIfAbsent(rootSite, ignored -> new HashSet<>()).add(deriv);
            for (Node prerequisite : prerequisites) {
                derivsUsing.computeIfAbsent(prerequisite, ignored -> new HashSet<>()).add(deriv);
            }
            if (prerequisites.stream().allMatch(present::contains)) {
                activate(produced);
            }
        }

        private void activate(Node node) {
            if (present.add(node)) {
                queued.add(node);
                worklist.add(node);
                if (node instanceof VFact v) {
                    varPts.computeIfAbsent(v.var(), ignored -> new TreeSet<>()).add(v.obj());
                } else if (node instanceof FFact f) {
                    fieldPts.computeIfAbsent(f.field(), ignored -> new TreeSet<>()).add(f.obj());
                } else if (node instanceof CopyNode c) {
                    copyIndex.computeIfAbsent(c.from(), ignored -> new ArrayList<>()).add(c);
                } else if (node instanceof LoadNode l) {
                    loadIndex.computeIfAbsent(l.base(), ignored -> new ArrayList<>()).add(l);
                    loadByFieldIndex.computeIfAbsent(l.field(), ignored -> new ArrayList<>())
                            .add(l);
                } else if (node instanceof StoreNode s) {
                    storeIndex.computeIfAbsent(s.from(), ignored -> new ArrayList<>()).add(s);
                }
            }
        }

        private void drain() {
            while (!worklist.isEmpty()) {
                List<Node> batch = new ArrayList<>(worklist);
                worklist.clear();
                Collections.sort(batch);
                for (Node node : batch) {
                    if (!present.contains(node) || !queued.remove(node)) {
                        continue;
                    }
                    fire(node);
                    satisfyWaiting(node);
                }
            }
        }

        private void satisfyWaiting(Node node) {
            for (Deriv deriv : new ArrayList<>(derivsUsing.getOrDefault(node, Set.of()))) {
                if (supportsOf.containsKey(deriv.produced())
                        && deriv.prerequisites().stream().allMatch(present::contains)) {
                    activate(deriv.produced());
                }
            }
        }

        private void fire(Node node) {
            if (node instanceof ReachNode reach) {
                fireReach(reach);
            } else if (node instanceof ActiveNode active) {
                fireActive(active);
            } else if (node instanceof CallNode call) {
                fireCall(call);
            } else if (node instanceof VFact vf) {
                fireVFact(vf);
            } else if (node instanceof FFact ff) {
                fireFFact(ff);
            } else if (node instanceof CopyNode c) {
                fireCopyEdge(c);
            } else if (node instanceof LoadNode l) {
                fireLoadEdge(l);
            } else if (node instanceof StoreNode s) {
                fireStoreEdge(s);
            }
        }

        private void fireCopyEdge(CopyNode edge) {
            for (Obj obj : sortedCopy(varPts.get(edge.from()))) {
                register(new VFact(edge.to(), obj), List.of(edge, new VFact(edge.from(), obj)),
                        edge.rootSite());
            }
        }

        private void fireLoadEdge(LoadNode edge) {
            for (Obj base : sortedCopy(varPts.get(edge.base()))) {
                InstanceFieldId field = new InstanceFieldId(base, edge.field());
                for (Obj target : sortedCopy(fieldPts.get(field))) {
                    register(new VFact(edge.to(), target),
                            List.of(edge, new VFact(edge.base(), base),
                                    new FFact(field, target)),
                            edge.rootSite());
                }
            }
        }

        private void fireStoreEdge(StoreNode edge) {
            for (Obj sourceObj : sortedCopy(varPts.get(edge.from()))) {
                for (Obj base : sortedCopy(varPts.get(edge.base()))) {
                    InstanceFieldId field = new InstanceFieldId(base, edge.field());
                    register(new FFact(field, sourceObj),
                            List.of(edge, new VFact(edge.from(), sourceObj),
                                    new VFact(edge.base(), base)),
                            edge.rootSite());
                }
            }
        }

        private void fireReach(ReachNode reach) {
            JMethod method = program.method(reach.method());
            if (method == null) {
                return;
            }
            for (Stmt stmt : method.body()) {
                ActiveNode active =
                        new ActiveNode(stmt.site(), reach.context(), reach.method());
                register(active, List.of(reach), stmt.site());
            }
        }

        private void fireActive(ActiveNode active) {
            Stmt stmt = statementAt(active.site());
            if (stmt == null) {
                return;
            }
            Context context = active.context();
            String methodKey = active.method();
            String site = active.site();
            if (stmt instanceof Stmt.New n) {
                Obj obj = new Obj(n.allocSite(), context);
                register(new VFact(new Var(context, methodKey, n.lhs()), obj),
                        List.of(active), site);
            } else if (stmt instanceof Stmt.Assign a) {
                register(new CopyNode(new Var(context, methodKey, a.rhs()),
                                new Var(context, methodKey, a.lhs()), site),
                        List.of(active), site);
            } else if (stmt instanceof Stmt.Load load) {
                register(new LoadNode(new Var(context, methodKey, load.base()), load.field(),
                                new Var(context, methodKey, load.lhs()), site),
                        List.of(active), site);
            } else if (stmt instanceof Stmt.Store store) {
                register(new StoreNode(new Var(context, methodKey, store.rhs()),
                                new Var(context, methodKey, store.base()), store.field(), site),
                        List.of(active), site);
            } else if (stmt instanceof Stmt.Invoke invoke) {
                fireInvoke(active, invoke, context, methodKey, site);
            }
        }

        private void fireInvoke(ActiveNode active, Stmt.Invoke invoke, Context context,
                                String methodKey, String site) {
            if (invoke.isStatic()) {
                String targetKey = invoke.className() + "#" + invoke.method()
                        + "/" + invoke.args().size();
                Context calleeContext = context.push(site, k);
                register(new CallNode(site, context, calleeContext, targetKey, site),
                        List.of(active), site);
            } else {
                Var receiver = new Var(context, methodKey, invoke.receiver());
                for (Obj obj : sortedCopy(varPts.get(receiver))) {
                    registerVirtualCalls(active, invoke, context, methodKey, site, receiver, obj);
                }
            }
        }

        private void registerVirtualCalls(ActiveNode active, Stmt.Invoke invoke, Context context,
                                          String methodKey, String site, Var receiver, Obj obj) {
            VFact receiverFact = new VFact(receiver, obj);
            List<String> targets = resolveVirtual(obj, invoke.className(), invoke.method(),
                    invoke.args().size());
            for (String targetKey : targets) {
                Context calleeContext = context.push(site, k);
                register(new CallNode(site, context, calleeContext, targetKey, site),
                        List.of(active, receiverFact), site);
            }
        }

        private void fireCall(CallNode call) {
            JMethod caller = methodContainingSite(call.site());
            Stmt.Invoke invoke = invokeAt(call.site());
            if (caller == null || invoke == null) {
                return;
            }
            register(new ReachNode(call.calleeContext(), call.target()),
                    List.of(call), call.rootSite());
            JMethod callee = program.method(call.target());
            if (callee == null) {
                return;
            }
            Context callerContext = call.callerContext();
            Context calleeContext = call.calleeContext();
            if (invoke.receiver() != null) {
                register(new CopyNode(
                                new Var(callerContext, caller.key(), invoke.receiver()),
                                new Var(calleeContext, call.target(), "this"),
                                call.rootSite()),
                        List.of(call), call.rootSite());
            }
            List<String> params = callee.params();
            for (int i = 0; i < Math.min(params.size(), invoke.args().size()); i++) {
                register(new CopyNode(
                                new Var(callerContext, caller.key(), invoke.args().get(i)),
                                new Var(calleeContext, call.target(), params.get(i)),
                                call.rootSite()),
                        List.of(call), call.rootSite());
            }
            if (invoke.lhs() != null) {
                Var lhs = new Var(callerContext, caller.key(), invoke.lhs());
                for (Stmt stmt : callee.body()) {
                    if (stmt instanceof Stmt.Return ret && ret.value() != null) {
                        register(new CopyNode(
                                        new Var(calleeContext, call.target(), ret.value()), lhs,
                                        call.rootSite()),
                                List.of(call), call.rootSite());
                    }
                }
            }
        }

        private void fireVFact(VFact vf) {
            for (CopyNode edge : copyEdgesFrom(vf.var())) {
                register(new VFact(edge.to(), vf.obj()), List.of(edge, vf), edge.rootSite());
            }
            for (LoadNode edge : loadEdgesFrom(vf.var())) {
                InstanceFieldId field = new InstanceFieldId(vf.obj(), edge.field());
                for (Obj target : sortedCopy(fieldPts.get(field))) {
                    register(new VFact(edge.to(), target),
                            List.of(edge, vf, new FFact(field, target)), edge.rootSite());
                }
            }
            for (StoreNode edge : storeEdgesFrom(vf.var())) {
                for (Obj base : sortedCopy(varPts.get(edge.base()))) {
                    InstanceFieldId field = new InstanceFieldId(base, edge.field());
                    register(new FFact(field, vf.obj()),
                            List.of(edge, new VFact(edge.base(), base), vf), edge.rootSite());
                }
            }
            fireNewReceiver(vf);
        }

        private void fireNewReceiver(VFact vf) {
            JMethod method = program.method(vf.var().method());
            if (method == null) {
                return;
            }
            for (Stmt stmt : method.body()) {
                if (!(stmt instanceof Stmt.Invoke invoke) || invoke.isStatic()) {
                    continue;
                }
                if (!invoke.receiver().equals(vf.var().name())) {
                    continue;
                }
                ActiveNode active = new ActiveNode(invoke.site(), vf.var().context(),
                        method.key());
                registerVirtualCalls(active, invoke, vf.var().context(), method.key(),
                        invoke.site(), vf.var(), vf.obj());
            }
        }

        private void fireFFact(FFact ff) {
            for (LoadNode edge : loadByFieldIndex.getOrDefault(ff.field().field(), List.of())) {
                VFact baseFact = new VFact(edge.base(), ff.field().base());
                register(new VFact(edge.to(), ff.obj()),
                        List.of(edge, baseFact, ff), edge.rootSite());
            }
        }

        private final Map<Var, List<CopyNode>> copyIndex = new HashMap<>();
        private final Map<Var, List<LoadNode>> loadIndex = new HashMap<>();
        private final Map<Var, List<StoreNode>> storeIndex = new HashMap<>();
        private final Map<String, List<LoadNode>> loadByFieldIndex = new HashMap<>();

        private long removalsSinceStart;
        private long registrationsSinceStart;
        private IncrementalStats incrementalStats;

        private List<CopyNode> copyEdgesFrom(Var from) {
            return copyIndex.getOrDefault(from, List.of());
        }

        private List<LoadNode> loadEdgesFrom(Var base) {
            return loadIndex.getOrDefault(base, List.of());
        }

        private List<StoreNode> storeEdgesFrom(Var from) {
            return storeIndex.getOrDefault(from, List.of());
        }

        private List<String> resolveVirtual(Obj receiver, String staticClass, String methodName,
                                            int arity) {
            String allocType = allocationType(receiver.allocSite());
            if (allocType == null) {
                allocType = staticClass;
            }
            List<String> result = new ArrayList<>();
            for (String className : classChain(allocType)) {
                JClass jClass = program.classNamed(className);
                if (jClass == null) {
                    continue;
                }
                for (JMethod method : sortedMethods(jClass)) {
                    if (!method.isStatic() && method.name().equals(methodName)
                            && method.params().size() == arity) {
                        result.add(method.key());
                        return result;
                    }
                }
            }
            return result;
        }

        private String allocationType(String allocSite) {
            return allocTypeBySite.get(allocSite);
        }

        private List<String> classChain(String className) {
            List<String> chain = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String current = className;
            while (current != null && seen.add(current)) {
                chain.add(current);
                JClass jClass = program.classNamed(current);
                current = jClass == null ? null : jClass.superName();
            }
            return chain;
        }

        private Stmt statementAt(String site) {
            return stmtBySite.get(site);
        }

        private JMethod methodContainingSite(String site) {
            return methodBySite.get(site);
        }

        private Stmt.Invoke invokeAt(String site) {
            Stmt stmt = statementAt(site);
            return stmt instanceof Stmt.Invoke invoke ? invoke : null;
        }

        private List<JClass> sortedClasses() {
            return sortedClassesOf(program);
        }

        private static List<JClass> sortedClassesOf(Program source) {
            return new ArrayList<>(new TreeMap<>(source.classes()).values());
        }

        private static List<JMethod> sortedMethods(JClass jClass) {
            return sortedMethodsOf(jClass);
        }

        private static List<JMethod> sortedMethodsOf(JClass jClass) {
            List<JMethod> methods = new ArrayList<>(jClass.methods());
            methods.sort((a, b) -> a.key().compareTo(b.key()));
            return methods;
        }

        private static <T> List<T> sortedCopy(java.util.Collection<T> items) {
            if (items == null) {
                return List.of();
            }
            List<T> list = new ArrayList<>(items);
            Collections.sort(list, null);
            return list;
        }

        private PointerAnalysisSnapshot materialize(long revision) {
            TreeMap<Var, TreeSet<Obj>> vars = new TreeMap<>();
            for (Map.Entry<Var, TreeSet<Obj>> entry : varPts.entrySet()) {
                vars.put(entry.getKey(), new TreeSet<>(entry.getValue()));
            }
            TreeMap<InstanceFieldId, TreeSet<Obj>> fields = new TreeMap<>();
            for (Map.Entry<InstanceFieldId, TreeSet<Obj>> entry : fieldPts.entrySet()) {
                fields.put(entry.getKey(), new TreeSet<>(entry.getValue()));
            }
            return new PointerAnalysisSnapshot(revision, config, program,
                    java.util.Collections.unmodifiableSortedMap(vars),
                    java.util.Collections.unmodifiableSortedMap(fields));
        }
    }
}
