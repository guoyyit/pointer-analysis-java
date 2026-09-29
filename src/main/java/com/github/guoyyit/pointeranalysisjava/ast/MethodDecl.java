package com.github.guoyyit.pointeranalysisjava.ast;

import java.util.List;

/**
 * A parsed method declaration.
 */
public record MethodDecl(String file, String name, List<String> params, List<Stmt> body,
                         boolean entry) {
}
