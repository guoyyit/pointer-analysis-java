package com.github.guoyyit.pointeranalysisjava.persistence;

/**
 * One recorded, reproducible query answer.
 */
public record QueryRecord(long sequence,
                          long revision,
                          String kind,
                          String context,
                          String left,
                          String right,
                          String answer) {
}
