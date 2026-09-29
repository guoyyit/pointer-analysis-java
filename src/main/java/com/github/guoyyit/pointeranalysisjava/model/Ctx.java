package com.github.guoyyit.pointeranalysisjava.model;

import java.util.List;

/**
 * A calling context: a stack of call-site identifiers of length at most k.
 */
public record Ctx(List<String> frames) implements Comparable<Ctx> {

    public static final Ctx EMPTY = new Ctx(List.of());

    @Override
    public int compareTo(Ctx other) {
        return this.frames.toString().compareTo(other.frames.toString());
    }
}
