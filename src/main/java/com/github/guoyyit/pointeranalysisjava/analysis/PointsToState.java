package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.model.Allocation;
import com.github.guoyyit.pointeranalysisjava.model.CVar;
import com.github.guoyyit.pointeranalysisjava.model.OField;

import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Raw solver state: points-to sets for context-qualified locals and for object fields.
 *
 * <p>All collections are sorted so iteration order, fixpoint worklist order and serialized
 * output are deterministic for identical inputs.</p>
 */
public final class PointsToState {

    private final SortedMap<CVar, SortedSet<Allocation>> locals = new TreeMap<>();
    private final SortedMap<OField, SortedSet<Allocation>> fields = new TreeMap<>();

    public SortedSet<Allocation> local(CVar variable) {
        return locals.computeIfAbsent(variable, ignored -> new TreeSet<>());
    }

    public SortedSet<Allocation> field(OField objectField) {
        return fields.computeIfAbsent(objectField, ignored -> new TreeSet<>());
    }

    public boolean hasLocal(CVar variable) {
        return locals.containsKey(variable);
    }

    public Map<CVar, SortedSet<Allocation>> localSets() {
        return locals;
    }

    public Map<OField, SortedSet<Allocation>> fieldSets() {
        return fields;
    }

    public PointsToState copy() {
        PointsToState clone = new PointsToState();
        locals.forEach((key, value) -> clone.locals.put(key, new TreeSet<>(value)));
        fields.forEach((key, value) -> clone.fields.put(key, new TreeSet<>(value)));
        return clone;
    }

    /**
     * Removes empty sets so two independent runs over the same program expose exactly the same
     * keys, regardless of which variables the worklist happened to visit.
     */
    public void normalize() {
        locals.values().removeIf(SortedSet::isEmpty);
        fields.values().removeIf(SortedSet::isEmpty);
    }
}
