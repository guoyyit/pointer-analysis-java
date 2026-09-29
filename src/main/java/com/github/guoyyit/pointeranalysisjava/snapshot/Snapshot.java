package com.github.guoyyit.pointeranalysisjava.snapshot;

import com.github.guoyyit.pointeranalysisjava.model.Allocation;
import com.github.guoyyit.pointeranalysisjava.model.CVar;
import com.github.guoyyit.pointeranalysisjava.model.OField;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An immutable, published analysis snapshot.
 *
 * <p>Once published, a snapshot instance never changes; analysis builds a successor snapshot
 * and atomically swaps the reference, so concurrent readers always observe either the old or
 * the new consistent state, never an interleaving of the two.</p>
 *
 * @param revision       monotonically increasing snapshot version
 * @param contextDepth   configured calling-context depth (k of k-CFA-style contexts)
 * @param localPointsTo  points-to sets keyed by context-qualified variables
 * @param fieldPointsTo  points-to sets keyed by allocation-site fields
 * @param sources        durable mirror of the analyzed source contents by file path
 * @param appliedChanges ordered set of change ids already incorporated (idempotent replay)
 */
public record Snapshot(long revision,
                       int contextDepth,
                       SortedMap<CVar, SortedSet<Allocation>> localPointsTo,
                       SortedMap<OField, SortedSet<Allocation>> fieldPointsTo,
                       SortedMap<String, String> sources,
                       SortedSet<String> appliedChanges) {

    public Snapshot {
        SortedMap<CVar, SortedSet<Allocation>> copiedLocals = new TreeMap<>();
        localPointsTo.forEach((key, value) -> copiedLocals.put(key,
                Collections.unmodifiableSortedSet(new TreeSet<>(value))));
        SortedMap<OField, SortedSet<Allocation>> copiedFields = new TreeMap<>();
        fieldPointsTo.forEach((key, value) -> copiedFields.put(key,
                Collections.unmodifiableSortedSet(new TreeSet<>(value))));
        localPointsTo = Collections.unmodifiableSortedMap(copiedLocals);
        fieldPointsTo = Collections.unmodifiableSortedMap(copiedFields);
        sources = Collections.unmodifiableSortedMap(new TreeMap<>(sources));
        appliedChanges = Collections.unmodifiableSortedSet(new TreeSet<>(appliedChanges));
    }

    public SortedSet<Allocation> pointsTo(CVar variable) {
        return localPointsTo.getOrDefault(variable, Collections.emptySortedSet());
    }

    public SortedSet<Allocation> pointsTo(OField objectField) {
        return fieldPointsTo.getOrDefault(objectField, Collections.emptySortedSet());
    }

    public Set<String> sourcePaths() {
        return sources.keySet();
    }

    public static Snapshot empty(int contextDepth) {
        return new Snapshot(0L, contextDepth, new TreeMap<>(), new TreeMap<>(),
                new TreeMap<>(), new TreeSet<>());
    }

    public static SortedMap<CVar, SortedSet<Allocation>> mutableLocalMap(Map<CVar,
            SortedSet<Allocation>> source) {
        SortedMap<CVar, SortedSet<Allocation>> copy = new TreeMap<>();
        source.forEach((key, value) -> copy.put(key, new TreeSet<>(value)));
        return copy;
    }

    public static SortedMap<OField, SortedSet<Allocation>> mutableFieldMap(Map<OField,
            SortedSet<Allocation>> source) {
        SortedMap<OField, SortedSet<Allocation>> copy = new TreeMap<>();
        source.forEach((key, value) -> copy.put(key, new TreeSet<>(value)));
        return copy;
    }
}
