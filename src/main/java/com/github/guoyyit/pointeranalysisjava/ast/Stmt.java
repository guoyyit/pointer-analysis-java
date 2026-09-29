package com.github.guoyyit.pointeranalysisjava.ast;

import java.util.List;

/**
 * Statements of the supported pointer-analysis language.
 */
public sealed interface Stmt
        permits Stmt.New, Stmt.Assign, Stmt.Load, Stmt.Store, Stmt.Call, Stmt.Return {

    record New(String lhs, String typeName, int line) implements Stmt {
    }

    record Assign(String lhs, String rhs) implements Stmt {
    }

    record Load(String lhs, String base, String field) implements Stmt {
    }

    record Store(String base, String field, String rhs) implements Stmt {
    }

    record Call(String lhs, String callee, List<String> args, int line) implements Stmt {
    }

    record Return(String value) implements Stmt {
    }
}
