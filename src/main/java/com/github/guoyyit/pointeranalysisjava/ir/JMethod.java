package com.github.guoyyit.pointeranalysisjava.ir;

import java.util.List;

/**
 * A parsed method. Methods are identified by {@code className#name/arity}.
 */
public record JMethod(String className, String name, List<String> params,
                      boolean isStatic, List<Stmt> body) {

    public JMethod {
        params = List.copyOf(params);
        body = List.copyOf(body);
    }

    public String key() {
        return className + "#" + name + "/" + params.size();
    }

    @Override
    public String toString() {
        return key();
    }
}
