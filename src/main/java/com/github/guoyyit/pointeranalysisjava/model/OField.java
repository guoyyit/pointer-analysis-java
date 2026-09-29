package com.github.guoyyit.pointeranalysisjava.model;

/**
 * An instance field of an abstract heap object.
 */
public record OField(Allocation allocation, String field) implements Comparable<OField> {

    @Override
    public int compareTo(OField other) {
        int byObject = this.allocation.compareTo(other.allocation);
        if (byObject != 0) {
            return byObject;
        }
        return this.field.compareTo(other.field);
    }
}
