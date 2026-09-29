package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.model.InstanceFieldId;
import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.Context;
import com.github.guoyyit.pointeranalysisjava.model.Obj;
import com.github.guoyyit.pointeranalysisjava.model.Var;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 * Immutable, published result of a pointer analysis at one revision.
 */
public record PointerAnalysisSnapshot(long revision,
                                      AnalysisConfig config,
                                      Program program,
                                      SortedMap<Var, SortedSet<Obj>> varPointsTo,
                                      SortedMap<InstanceFieldId, SortedSet<Obj>> fieldPointsTo) {

    public SortedSet<Obj> pointsTo(Var var) {
        return varPointsTo.getOrDefault(var, java.util.Collections.emptySortedSet());
    }

    public boolean mayAlias(Context context, AccessPath left, AccessPath right) {
        List<PathTarget> leftTargets = evaluatePath(context, left);
        List<PathTarget> rightTargets = evaluatePath(context, right);
        for (PathTarget lt : leftTargets) {
            for (PathTarget rt : rightTargets) {
                if (!lt.context().equals(rt.context())) {
                    continue;
                }
                SortedSet<Obj> intersection = new TreeSet<>(lt.targets());
                intersection.retainAll(rt.targets());
                if (!intersection.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<PathTarget> evaluatePath(Context context, AccessPath path) {
        String method = path.method() != null ? path.method()
                : config.entryClass() + "#" + config.entryMethod() + "/0";
        List<PathTarget> results = new ArrayList<>();
        for (Map.Entry<Var, SortedSet<Obj>> entry : varPointsTo.entrySet()) {
            Var root = entry.getKey();
            if (!root.name().equals(path.root()) || !root.method().equals(method)) {
                continue;
            }
            if (context != null && !root.context().equals(context)) {
                continue;
            }
            SortedSet<Obj> targets = dereference(entry.getValue(), path.fields());
            results.add(new PathTarget(root.context(),
                    new AccessPath(method, path.root(), path.fields()), targets));
        }
        results.sort((a, b) -> a.context().compareTo(b.context()));
        return results;
    }

    private SortedSet<Obj> dereference(SortedSet<Obj> bases, List<String> fields) {
        SortedSet<Obj> current = new TreeSet<>(bases);
        for (String field : fields) {
            SortedSet<Obj> next = new TreeSet<>();
            for (Obj base : current) {
                next.addAll(fieldPointsTo.getOrDefault(new InstanceFieldId(base, field),
                        java.util.Collections.emptySortedSet()));
            }
            current = next;
        }
        return current;
    }

    public SortedMap<Context, SortedSet<Obj>> rootSummary(String method, String root) {
        SortedMap<Context, SortedSet<Obj>> summary = new TreeMap<>();
        for (Map.Entry<Var, SortedSet<Obj>> entry : varPointsTo.entrySet()) {
            Var var = entry.getKey();
            if (var.name().equals(root) && var.method().equals(method)) {
                summary.put(var.context(), entry.getValue());
            }
        }
        return summary;
    }

    /** One concrete context-qualified target of an access path query. */
    public record PathTarget(Context context, AccessPath path, SortedSet<Obj> targets) {
    }
}
