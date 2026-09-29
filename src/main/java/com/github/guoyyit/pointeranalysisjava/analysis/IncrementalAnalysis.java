package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.ast.MethodDecl;
import com.github.guoyyit.pointeranalysisjava.ast.Program;
import com.github.guoyyit.pointeranalysisjava.ast.Stmt;
import com.github.guoyyit.pointeranalysisjava.model.Allocation;
import com.github.guoyyit.pointeranalysisjava.model.CVar;
import com.github.guoyyit.pointeranalysisjava.model.OField;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.snapshot.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * File-level incremental pointer analysis.
 *
 * <p>On a file change, constraints are regenerated for the new program (only reachable
 * contexts are enumerated) and the previous points-to state is reused as the initial state.
 * Every contextual variable belonging to a changed method, plus every parameter/result
 * variable at call sites that enter or leave changed methods, is seeded as dirty. The
 * {@link AndersenSolver} clears the dependency-reachable region and re-derives it under the
 * new constraints; facts outside that region are never touched.</p>
 *
 * <p>Reachable-context enumeration and dependency closure guarantee the produced snapshot is
 * identical to a full rebuild of the new program; {@code verifyAgainstFullRebuild} compares
 * the two explicitly.</p>
 */
public final class IncrementalAnalysis {

    private final AnalysisConfig config;

    public IncrementalAnalysis(AnalysisConfig config) {
        this.config = config;
    }

    public Snapshot update(Snapshot previous, Program oldProgram, Program newProgram,
                           Set<String> changedFiles, SortedMap<String, String> newSources,
                           SortedSet<String> appliedChanges) {
        return updateWithStats(previous, oldProgram, newProgram, changedFiles, newSources,
                appliedChanges).snapshot();
    }

    public UpdateResult updateWithStats(Snapshot previous, Program oldProgram, Program newProgram,
                                        Set<String> changedFiles,
                                        SortedMap<String, String> newSources,
                                        SortedSet<String> appliedChanges) {
        ConstraintGenerator generator =
                new ConstraintGenerator(newProgram, config.getContextDepth());
        ConstraintGenerator.Result generated = generator.generate();

        PointsToState state = new PointsToState();
        previous.localPointsTo().forEach((key, value) -> state.local(key).addAll(value));
        previous.fieldPointsTo().forEach((key, value) -> state.field(key).addAll(value));

        Set<String> changedMethods = changedMethods(oldProgram, newProgram, changedFiles);
        SeedSet seedSet = collectSeeds(previous, generated, changedMethods, newProgram, state);
        java.util.SortedSet<OField> staleFields = new TreeSet<>();
        for (OField objectField : previous.fieldPointsTo().keySet()) {
            if (changedMethods.contains(objectField.allocation().methodName())) {
                staleFields.add(objectField);
            }
        }

        AndersenSolver solver = new AndersenSolver(
                generated.constraints(), state, config.getParallelism());
        solver.update(seedSet.seeds(), seedSet.taintedSources(), staleFields);
        int processed = solver.processedVariables();
        int affected = solver.affectedVariableCount();

        TreeMap<CVar, SortedSet<Allocation>> locals = new TreeMap<>();
        state.localSets().forEach((key, value) -> locals.put(key, new TreeSet<>(value)));
        TreeMap<OField, SortedSet<Allocation>> fields = new TreeMap<>();
        state.fieldSets().forEach((key, value) -> fields.put(key, new TreeSet<>(value)));
        Snapshot snapshot = new Snapshot(previous.revision() + 1, config.getContextDepth(),
                locals, fields, newSources, appliedChanges);
        return new UpdateResult(snapshot, processed, affected, generated.constraints().size());
    }

    public record UpdateResult(Snapshot snapshot, int processedVariables,
                               int affectedVariables, int constraintCount) {
    }

    private Set<String> changedMethods(Program oldProgram, Program newProgram,
                                       Set<String> changedFiles) {
        Set<String> methods = new TreeSet<>();
        if (oldProgram != null) {
            for (MethodDecl method : oldProgram.methods().values()) {
                if (changedFiles.contains(method.file())) {
                    methods.add(method.name());
                }
            }
        }
        for (MethodDecl method : newProgram.methods().values()) {
            if (changedFiles.contains(method.file())) {
                methods.add(method.name());
            }
        }
        return methods;
    }

    private SeedSet collectSeeds(Snapshot previous, ConstraintGenerator.Result generated,
                                 Set<String> changedMethods, Program newProgram,
                                 PointsToState state) {
        SortedSet<CVar> seeds = new TreeSet<>();
        // 1. All observed contextual variables of changed methods (old contexts included).
        for (CVar variable : previous.localPointsTo().keySet()) {
            if (changedMethods.contains(variable.method())) {
                seeds.add(variable);
            }
        }
        // 2. Variables generated for changed methods under the new program (new contexts).
        for (ConstraintGenerator.ContextEntry entry : generated.reachedContexts()) {
            if (changedMethods.contains(entry.method())) {
                MethodDecl method = newProgram.methods().get(entry.method());
                if (method != null) {
                    for (String local : localsOf(method)) {
                        seeds.add(new CVar(entry.ctx(), entry.method(), local));
                    }
                }
            }
        }
        SortedSet<CVar> tainted = new TreeSet<>();
        // 3. Copy constraints crossing the change boundary: the unchanged-file endpoint is a
        //    tainted external source whose facts replay into the re-solved region.
        for (Constraint constraint : generated.constraints()) {
            if (constraint instanceof Constraint.Copy copy) {
                boolean lhsChanged = changedMethods.contains(copy.lhs().method());
                boolean rhsChanged = changedMethods.contains(copy.rhs().method());
                if (lhsChanged) {
                    seeds.add(copy.lhs());
                    state.local(copy.lhs());
                }
                if (rhsChanged) {
                    seeds.add(copy.rhs());
                    state.local(copy.rhs());
                }
                if (lhsChanged && !rhsChanged) {
                    tainted.add(copy.rhs());
                    state.local(copy.rhs());
                }
                if (rhsChanged && !lhsChanged) {
                    tainted.add(copy.lhs());
                    state.local(copy.lhs());
                }
            }
        }
        return new SeedSet(new ArrayList<>(seeds), new ArrayList<>(tainted));
    }

    private record SeedSet(List<CVar> seeds, List<CVar> taintedSources) {
    }

    private Set<String> localsOf(MethodDecl method) {
        Set<String> locals = new TreeSet<>(method.params());
        locals.add(ConstraintGenerator.RETURN_VARIABLE);
        for (Stmt statement : method.body()) {
            if (statement instanceof Stmt.New allocation) {
                locals.add(allocation.lhs());
            } else if (statement instanceof Stmt.Assign assign) {
                locals.add(assign.lhs());
            } else if (statement instanceof Stmt.Load load) {
                locals.add(load.lhs());
            } else if (statement instanceof Stmt.Call call
                    && call.lhs() != null && !call.lhs().isBlank()) {
                locals.add(call.lhs());
            }
        }
        return locals;
    }
}
