package com.github.guoyyit.pointeranalysisjava.parser;

import com.github.guoyyit.pointeranalysisjava.ir.Program;

/**
 * Result of a parse run: the deterministic {@link Program} plus observable
 * {@link ParseStats execution statistics}.
 */
public record ParseOutcome(Program program, ParseStats stats) {
}
