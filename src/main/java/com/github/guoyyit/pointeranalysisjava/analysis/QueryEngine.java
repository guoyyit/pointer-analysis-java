package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.Allocation;
import com.github.guoyyit.pointeranalysisjava.model.CVar;
import com.github.guoyyit.pointeranalysisjava.model.Ctx;
import com.github.guoyyit.pointeranalysisjava.model.OField;
import com.github.guoyyit.pointeranalysisjava.snapshot.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Read-only query evaluation over a published {@link Snapshot}.
 *
 * <p>An access path {@code a.f.g} is resolved by starting with the points-to set of the
 * context-qualified root variable and unioning the points-to set of each object's field for
 * every dereference step. Two paths may-alias exactly when their resolved sets intersect;
 * empty intersections are reported as not-alias (including paths that never point anywhere).
 * All computations are functions of a single immutable snapshot, so answers are stable for
 * as long as that revision remains published.</p>
 */
public final class QueryEngine {

    public SortedSet<Allocation> resolve(Snapshot snapshot, CVar root, AccessPath path) {
        SortedSet<Allocation> current = new TreeSet<>(snapshot.pointsTo(root));
        for (String field : path.fields()) {
            SortedSet<Allocation> next = new TreeSet<>();
            for (Allocation allocation : current) {
                next.addAll(snapshot.pointsTo(new OField(allocation, field)));
            }
            current = next;
            if (current.isEmpty()) {
                return current;
            }
        }
        return current;
    }

    public SortedSet<Allocation> resolveExpression(Snapshot snapshot, CVar root,
                                                   String expression) {
        return resolve(snapshot, root, parseExpression(expression, root.name()));
    }

    public boolean mayAlias(Snapshot snapshot, CVar rootA, AccessPath pathA,
                            CVar rootB, AccessPath pathB) {
        if (rootA == null || rootB == null) {
            return false;
        }
        SortedSet<Allocation> setA = resolve(snapshot, rootA, pathA);
        SortedSet<Allocation> setB = resolve(snapshot, rootB, pathB);
        for (Allocation allocation : setA) {
            if (setB.contains(allocation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses {@code a.f.g} into an access path. A bare variable yields an empty field chain.
     */
    public AccessPath parseExpression(String expression, String defaultBase) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isEmpty()) {
            return AccessPath.of(defaultBase, List.of());
        }
        String[] parts = trimmed.split("\\.");
        String base = parts[0].trim();
        if (base.isEmpty()) {
            base = defaultBase;
        }
        List<String> fields = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String field = parts[i].trim();
            if (field.isEmpty()) {
                throw new IllegalArgumentException("Empty field in expression: " + expression);
            }
            fields.add(field);
        }
        return AccessPath.of(base, fields);
    }

    /**
     * Parses the canonical context representation {@code [file:line,file:line]}; blank or
     * {@code []} denotes the empty (entry) context.
     */
    public Ctx parseContext(String context) {
        if (context == null || context.isBlank() || context.trim().equals("[]")) {
            return Ctx.EMPTY;
        }
        String trimmed = context.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException(
                    "Context must look like [file:line,file:line] or []: " + context);
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return Ctx.EMPTY;
        }
        List<String> frames = new ArrayList<>();
        for (String frame : body.split(",")) {
            frames.add(frame.trim());
        }
        return new Ctx(List.copyOf(frames));
    }

    public String formatContext(Ctx ctx) {
        return "[" + String.join(",", ctx.frames()) + "]";
    }

    public CVar resolveRoot(Snapshot snapshot, String method, Ctx ctx, String variable) {
        CVar exact = new CVar(ctx, method, variable);
        if (snapshot.localPointsTo().containsKey(exact)) {
            return exact;
        }
        return null;
    }
}
