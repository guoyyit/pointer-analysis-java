package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.ast.MethodDecl;
import com.github.guoyyit.pointeranalysisjava.ast.Program;
import com.github.guoyyit.pointeranalysisjava.ast.Stmt;
import com.github.guoyyit.pointeranalysisjava.model.Allocation;
import com.github.guoyyit.pointeranalysisjava.model.CVar;
import com.github.guoyyit.pointeranalysisjava.model.Ctx;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates context-sensitive Andersen constraints for all methods reachable from entries.
 *
 * <p>Context model: a context is the bounded stack of call-site identifiers
 * {@code file:line} that led to the current method (a k-CFA-style call-string approach with
 * k = {@code contextDepth}). The same local in two contexts is represented by distinct
 * {@link CVar}s, so points-to answers are context-specific. Heap objects are identified by
 * their allocation site and are shared across contexts (context-insensitive heap), matching
 * the documented semantics.</p>
 *
 * <p>Method dispatch is by unique method name. A call argument {@code a_i} binds to formal
 * parameter {@code p_i} via a copy constraint; a non-void return value binds to the call
 * destination through a per-call-site synthetic return variable.</p>
 */
public final class ConstraintGenerator {

    public static final String RETURN_VARIABLE = "$ret";

    private final Program program;
    private final int contextDepth;
    private final Map<String, List<Allocation>> allocationsByMethod = new TreeMap<>();

    public ConstraintGenerator(Program program, int contextDepth) {
        if (contextDepth < 0) {
            throw new IllegalArgumentException("contextDepth must be non-negative");
        }
        this.program = program;
        this.contextDepth = contextDepth;
        indexAllocations();
    }

    public Result generate() {
        List<Constraint> constraints = new ArrayList<>();
        Set<ContextEntry> reached = new LinkedHashSet<>();
        List<ContextEntry> worklist = new ArrayList<>();
        for (MethodDecl method : sortedMethods()) {
            if (method.entry()) {
                ContextEntry entry = new ContextEntry(Ctx.EMPTY, method.name());
                if (reached.add(entry)) {
                    worklist.add(entry);
                }
            }
        }
        if (reached.isEmpty() && !program.methods().isEmpty()) {
            MethodDecl first = sortedMethods().get(0);
            ContextEntry entry = new ContextEntry(Ctx.EMPTY, first.name());
            reached.add(entry);
            worklist.add(entry);
        }
        int cursor = 0;
        while (cursor < worklist.size()) {
            ContextEntry current = worklist.get(cursor++);
            MethodDecl method = program.methods().get(current.method());
            if (method == null) {
                throw new IllegalStateException("Missing method: " + current.method());
            }
            constraints.addAll(generate(current.ctx(), method, worklist, reached));
        }
        return new Result(List.copyOf(constraints), List.copyOf(worklist));
    }

    public record ContextEntry(Ctx ctx, String method) implements Comparable<ContextEntry> {
        @Override
        public int compareTo(ContextEntry other) {
            int byContext = this.ctx.compareTo(other.ctx);
            if (byContext != 0) {
                return byContext;
            }
            return this.method.compareTo(other.method);
        }
    }

    public record Result(List<Constraint> constraints, List<ContextEntry> reachedContexts) {
    }

    private List<Constraint> generate(Ctx ctx, MethodDecl method, List<ContextEntry> worklist,
                                      Set<ContextEntry> reached) {
        List<Constraint> generated = new ArrayList<>();
        for (Stmt statement : method.body()) {
            if (statement instanceof Stmt.New allocation) {
                Allocation object = findAllocation(method.name(), allocation.line());
                generated.add(new Constraint.NewC(
                        new CVar(ctx, method.name(), allocation.lhs()), object));
            } else if (statement instanceof Stmt.Assign assign) {
                generated.add(new Constraint.Copy(
                        new CVar(ctx, method.name(), assign.lhs()),
                        new CVar(ctx, method.name(), assign.rhs())));
            } else if (statement instanceof Stmt.Load load) {
                generated.add(new Constraint.Load(
                        new CVar(ctx, method.name(), load.lhs()),
                        new CVar(ctx, method.name(), load.base()), load.field()));
            } else if (statement instanceof Stmt.Store store) {
                generated.add(new Constraint.Store(
                        new CVar(ctx, method.name(), store.base()), store.field(),
                        new CVar(ctx, method.name(), store.rhs())));
            } else if (statement instanceof Stmt.Call call) {
                MethodDecl callee = program.methods().get(call.callee());
                if (callee == null) {
                    throw new IllegalArgumentException(
                            "Undefined callee '" + call.callee() + "' in method " + method.name()
                                    + " (" + method.file() + ":" + call.line() + ")");
                }
                if (call.args().size() != callee.params().size()) {
                    throw new IllegalArgumentException(
                            "Arity mismatch calling " + callee.name() + " at " + method.file()
                                    + ":" + call.line());
                }
                String callSite = method.file() + ":" + call.line();
                Ctx calleeContext = pushFrame(ctx, callSite);
                for (int i = 0; i < call.args().size(); i++) {
                    generated.add(new Constraint.Copy(
                            new CVar(calleeContext, callee.name(), callee.params().get(i)),
                            new CVar(ctx, method.name(), call.args().get(i))));
                }
                ContextEntry next = new ContextEntry(calleeContext, callee.name());
                if (reached.add(next)) {
                    worklist.add(next);
                }
                if (call.lhs() != null && !call.lhs().isBlank()) {
                    generated.add(new Constraint.Copy(
                            new CVar(ctx, method.name(), call.lhs()),
                            new CVar(calleeContext, callee.name(), RETURN_VARIABLE)));
                }
            } else if (statement instanceof Stmt.Return ret && ret.value() != null) {
                generated.add(new Constraint.Copy(
                        new CVar(ctx, method.name(), RETURN_VARIABLE),
                        new CVar(ctx, method.name(), ret.value())));
            }
        }
        return generated;
    }

    private Ctx pushFrame(Ctx current, String callSite) {
        List<String> frames = new ArrayList<>(current.frames());
        frames.add(callSite);
        while (frames.size() > contextDepth) {
            frames.remove(0);
        }
        return new Ctx(List.copyOf(frames));
    }

    private Allocation findAllocation(String method, int line) {
        return allocationsByMethod.getOrDefault(method, List.of()).stream()
                .filter(allocation -> allocation.siteId().contains(":" + line + "@"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No allocation at " + method + ":" + line));
    }

    private void indexAllocations() {
        for (MethodDecl method : sortedMethods()) {
            List<Allocation> sites = new ArrayList<>();
            for (Stmt statement : method.body()) {
                if (statement instanceof Stmt.New allocation) {
                    String siteId = method.file() + ":" + allocation.line() + "@"
                            + allocation.lhs();
                    sites.add(new Allocation(siteId, allocation.typeName(), method.name()));
                }
            }
            if (!sites.isEmpty()) {
                allocationsByMethod.put(method.name(), List.copyOf(sites));
            }
        }
    }

    private List<MethodDecl> sortedMethods() {
        return new ArrayList<>(new TreeMap<>(program.methods()).values());
    }
}
