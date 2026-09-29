package com.github.guoyyit.pointeranalysisjava.model;

import java.util.List;

/**
 * A root variable followed by zero or more field dereferences, e.g. {@code a.f.g}.
 */
public record AccessPath(String base, List<String> fields) {

    public static AccessPath of(String base, List<String> fields) {
        return new AccessPath(base, List.copyOf(fields));
    }
}
