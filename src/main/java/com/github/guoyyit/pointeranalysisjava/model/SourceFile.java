package com.github.guoyyit.pointeranalysisjava.model;

/**
 * A single input source file identified by a repository-relative path.
 */
public record SourceFile(String path, String content) {
}
