package com.github.guoyyit.pointeranalysisjava.model;

/**
 * A context-qualified local variable or formal parameter.
 */
public record CVar(Ctx ctx, String method, String name) implements Comparable<CVar> {

    @Override
    public int compareTo(CVar other) {
        int byCtx = this.ctx.compareTo(other.ctx);
        if (byCtx != 0) {
            return byCtx;
        }
        int byMethod = this.method.compareTo(other.method);
        if (byMethod != 0) {
            return byMethod;
        }
        return this.name.compareTo(other.name);
    }
}
