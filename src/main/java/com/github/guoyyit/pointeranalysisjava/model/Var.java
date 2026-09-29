package com.github.guoyyit.pointeranalysisjava.model;

/**
 * A context-qualified local variable or formal parameter.
 */
public record Var(Context context, String method, String name) implements Comparable<Var> {

    @Override
    public int compareTo(Var other) {
        int cmp = context.compareTo(other.context);
        if (cmp != 0) {
            return cmp;
        }
        cmp = method.compareTo(other.method);
        if (cmp != 0) {
            return cmp;
        }
        return name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return context + "::" + method + "#" + name;
    }
}
