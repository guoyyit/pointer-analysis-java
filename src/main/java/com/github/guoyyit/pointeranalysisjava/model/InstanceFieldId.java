package com.github.guoyyit.pointeranalysisjava.model;

/**
 * Address of an instance field: a concrete heap object paired with a field name.
 */
public record InstanceFieldId(Obj base, String field) implements Comparable<InstanceFieldId> {

    @Override
    public int compareTo(InstanceFieldId other) {
        int cmp = base.compareTo(other.base);
        if (cmp != 0) {
            return cmp;
        }
        return field.compareTo(other.field);
    }

    @Override
    public String toString() {
        return base + "." + field;
    }
}
