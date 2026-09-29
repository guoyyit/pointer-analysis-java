package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.model.Allocation;
import com.github.guoyyit.pointeranalysisjava.model.CVar;

/**
 * Andersen-style set-constraint over context-qualified variables and object fields.
 */
public sealed interface Constraint
        permits Constraint.NewC, Constraint.Copy, Constraint.Load, Constraint.Store {

    record NewC(CVar lhs, Allocation object) implements Constraint {
    }

    record Copy(CVar lhs, CVar rhs) implements Constraint {
    }

    record Load(CVar lhs, CVar base, String field) implements Constraint {
    }

    record Store(CVar base, String field, CVar rhs) implements Constraint {
    }
}
