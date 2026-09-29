package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.model.SourceFile;

import java.util.List;

/**
 * Shared source programs for the pointer analysis tests.
 */
final class TestFixtures {

    private TestFixtures() {
    }

    /**
     * get() is called from two distinct sites with allocations of different types, so its
     * parameter must be distinguished by calling context.
     */
    static final String TWO_CALL_SITES = """
            entry void main() {
              new a = new A();
              new b = new B();
              call x = get(a);
              call y = get(b);
            }
            void get(p) {
              copy q = p;
              return q;
            }
            """;

    /**
     * Field flow: x self-stores its field, the identity helper propagates the object and a
     * load reads the field back.
     */
    static final String FIELD_FLOW = """
            entry void main() {
              new x = new Node();
              store x.next = x;
              call y = id(x);
              load z = y.next;
            }
            void id(p) {
              return p;
            }
            """;

    /**
     * Two disjoint allocations used to check may-alias negative cases.
     */
    static final String DISJOINT = """
            entry void main() {
              new u = new U();
              new v = new V();
            }
            """;

    static List<SourceFile> single(String path, String content) {
        return List.of(new SourceFile(path, content));
    }
}
