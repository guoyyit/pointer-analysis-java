package com.github.guoyyit.pointeranalysisjava.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Statement of the supported Java subset. Every statement carries a unique
 * stable site identifier derived from its source location.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Stmt.New.class, name = "new"),
        @JsonSubTypes.Type(value = Stmt.Assign.class, name = "assign"),
        @JsonSubTypes.Type(value = Stmt.Load.class, name = "load"),
        @JsonSubTypes.Type(value = Stmt.Store.class, name = "store"),
        @JsonSubTypes.Type(value = Stmt.Invoke.class, name = "invoke"),
        @JsonSubTypes.Type(value = Stmt.Return.class, name = "return")
})
public sealed interface Stmt
        permits Stmt.New, Stmt.Assign, Stmt.Load, Stmt.Store, Stmt.Invoke, Stmt.Return {

    String site();

    /** lhs = new Type() */
    record New(String site, String lhs, String type, String allocSite) implements Stmt {
    }

    /** lhs = rhs (local copy) */
    record Assign(String site, String lhs, String rhs) implements Stmt {
    }

    /** lhs = base.field */
    record Load(String site, String lhs, String base, String field) implements Stmt {
    }

    /** base.field = rhs */
    record Store(String site, String base, String field, String rhs) implements Stmt {
    }

    /**
     * [lhs =] receiver.className.method(args) or ClassName.method(args).
     * lhs/receiver are {@code null} when absent.
     */
    record Invoke(String site, String lhs, String receiver, String className, String method,
                  List<String> args, boolean isStatic) implements Stmt {
        public Invoke {
            args = List.copyOf(args);
        }
    }

    /** return [value]; value is {@code null} for void returns. */
    record Return(String site, String value) implements Stmt {
    }
}
