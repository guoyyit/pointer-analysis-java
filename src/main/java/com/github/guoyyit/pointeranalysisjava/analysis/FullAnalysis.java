package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.ast.Program;
import com.github.guoyyit.pointeranalysisjava.model.Allocation;
import com.github.guoyyit.pointeranalysisjava.model.CVar;
import com.github.guoyyit.pointeranalysisjava.model.OField;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.snapshot.Snapshot;

import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Whole-program pointer analysis from scratch.
 *
 * <p>Used both for initial builds and as the reference implementation that incremental
 * results are verified against.</p>
 */
public final class FullAnalysis {

    private final AnalysisConfig config;

    public FullAnalysis(AnalysisConfig config) {
        this.config = config;
    }

    public Snapshot analyze(Program program) {
        return analyze(program, new TreeMap<>(), new TreeSet<>(), 0L);
    }

    public Snapshot analyze(Program program,
                            java.util.SortedMap<String, String> sources,
                            java.util.SortedSet<String> appliedChanges,
                            long baseRevision) {
        ConstraintGenerator generator =
                new ConstraintGenerator(program, config.getContextDepth());
        ConstraintGenerator.Result generated = generator.generate();
        PointsToState state = new PointsToState();
        AndersenSolver solver = new AndersenSolver(
                generated.constraints(), state, config.getParallelism());
        solver.solve();
        TreeMap<CVar, SortedSet<Allocation>> locals = new TreeMap<>();
        state.localSets().forEach((key, value) -> locals.put(key, new TreeSet<>(value)));
        TreeMap<OField, SortedSet<Allocation>> fields = new TreeMap<>();
        state.fieldSets().forEach((key, value) -> fields.put(key, new TreeSet<>(value)));
        return new Snapshot(baseRevision + 1, config.getContextDepth(),
                locals, fields, sources, appliedChanges);
    }
}
