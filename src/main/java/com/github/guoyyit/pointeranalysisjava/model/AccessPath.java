package com.github.guoyyit.pointeranalysisjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An access path rooted at a local variable: root.f1.f2...
 * The optional {@code method} scopes a bare root name; when {@code null} the
 * entry method is assumed.
 */
public record AccessPath(String method, String root, List<String> fields) {

    public AccessPath {
        fields = List.copyOf(fields);
    }

    public static AccessPath local(String root) {
        return new AccessPath(null, root, List.of());
    }

    public static AccessPath local(String method, String root) {
        return new AccessPath(method, root, List.of());
    }

    public AccessPath append(String field) {
        List<String> next = new ArrayList<>(fields);
        next.add(field);
        return new AccessPath(method, root, next);
    }

    @Override
    public String toString() {
        return (method == null ? "" : method + "#") + root
                + (fields.isEmpty() ? "" : "." + String.join(".", fields));
    }
}
