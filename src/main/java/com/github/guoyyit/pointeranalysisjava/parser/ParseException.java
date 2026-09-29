package com.github.guoyyit.pointeranalysisjava.parser;

/**
 * Thrown when a source file of the supported Java subset cannot be parsed.
 */
public class ParseException extends RuntimeException {

    public ParseException(String message) {
        super(message);
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
