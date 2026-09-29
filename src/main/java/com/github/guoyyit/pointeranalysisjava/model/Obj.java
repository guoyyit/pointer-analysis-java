package com.github.guoyyit.pointeranalysisjava.model;

/**
 * A heap object identified by its allocation site and the heap context
 * (the k-limited calling context active at the allocation).
 */
public record Obj(String allocSite, Context heapContext) implements Comparable<Obj> {

    @Override
    public int compareTo(Obj other) {
        int cmp = allocSite.compareTo(other.allocSite);
        if (cmp != 0) {
            return cmp;
        }
        return heapContext.compareTo(other.heapContext);
    }

    @Override
    public String toString() {
        return allocSite + "@" + heapContext;
    }
}
