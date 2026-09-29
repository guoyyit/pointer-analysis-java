package com.github.guoyyit.pointeranalysisjava.ir;

import java.util.List;

/**
 * A parsed class of the supported Java subset.
 */
public record JClass(String name, String superName, List<JMethod> methods) {

    public JClass {
        methods = List.copyOf(methods);
    }
}
