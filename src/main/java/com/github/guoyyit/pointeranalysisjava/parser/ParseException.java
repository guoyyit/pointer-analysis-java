package com.github.guoyyit.pointeranalysisjava.parser;

/**
 * Thrown when source input cannot be parsed.
 */
public class ParseException extends RuntimeException {

    public ParseException(String message) {
        super(message);
    }
}
