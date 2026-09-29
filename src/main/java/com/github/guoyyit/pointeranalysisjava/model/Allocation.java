package com.github.guoyyit.pointeranalysisjava.model;

/**
 * An abstract heap object identified by its allocation site.
 */
public record Allocation(String siteId, String typeName, String methodName)
        implements Comparable<Allocation> {

    @Override
    public int compareTo(Allocation other) {
        return this.siteId.compareTo(other.siteId);
    }
}
