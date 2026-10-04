package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.analysis.ChangeSet;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSolver;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.Context;
import com.github.guoyyit.pointeranalysisjava.parser.SourceParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisEngineTest {

    private PointerAnalysisSnapshot solve(String file, String content, int k) {
        Program program = SourceParser.parse(Map.of(file, content));
        return new PointerAnalysisSolver(new AnalysisConfig(k, 1, "Main", "main")).solve(program);
    }

    @Test
    void contextInsensitiveMergesCallers() {
        String src = callersProgram();
        PointerAnalysisSnapshot k0 = solve("M.java", src, 0);
        PointerAnalysisSnapshot k1 = solve("M.java", src, 1);

        long k0Contexts = k0.varPointsTo().keySet().stream()
                .filter(v -> v.method().equals("Id#id/1")).map(v -> v.context())
                .distinct().count();
        long k1Contexts = k1.varPointsTo().keySet().stream()
                .filter(v -> v.method().equals("Id#id/1")).map(v -> v.context())
                .distinct().count();
        assertEquals(1L, k0Contexts, "k=0 merges both callers into one context");
        assertEquals(2L, k1Contexts, "k=1 keeps two distinct callee contexts");
        k0.varPointsTo().keySet().stream()
                .filter(v -> v.method().equals("Id#id/1") && v.name().equals("p"))
                .forEach(v -> assertEquals(2, k0.pointsTo(v).size(),
                        "k=0 parameter points to both allocations"));
        k1.varPointsTo().keySet().stream()
                .filter(v -> v.method().equals("Id#id/1") && v.name().equals("p"))
                .forEach(v -> assertEquals(1, k1.pointsTo(v).size(),
                        "k=1 parameter points to a single allocation"));
    }

    @Test
    void resultsAreLabeledWithContext() {
        PointerAnalysisSnapshot k1 = solve("M.java", callersProgram(), 1);
        var p = k1.evaluatePath(null, AccessPath.local("Id#id/1", "p"));
        assertEquals(2, p.size());
        p.forEach(target -> assertFalse(target.context().isRoot(),
                "callee parameter carries its calling context"));
    }

    @Test
    void mayAliasAndNotAlias() {
        String src = """
                class Main {
                  static void main() {
                    A a1 = new A();
                    A a2 = new A();
                    A x = a1;
                    A y = a2;
                    A z = a1;
                  }
                }
                class A {
                }
                """;
        PointerAnalysisSnapshot snap = solve("M.java", src, 1);
        assertTrue(snap.mayAlias(Context.ROOT,
                AccessPath.local("Main#main/0", "a1"),
                AccessPath.local("Main#main/0", "x")));
        assertTrue(snap.mayAlias(Context.ROOT,
                AccessPath.local("Main#main/0", "a1"),
                AccessPath.local("Main#main/0", "z")));
        assertFalse(snap.mayAlias(Context.ROOT,
                AccessPath.local("Main#main/0", "a1"),
                AccessPath.local("Main#main/0", "a2")));
    }

    @Test
    void incrementalMatchesFullAcrossEdits() {
        String[] edits = {
                """
                class Main {
                  static void main() {
                    A a = new A();
                  }
                }
                class A {
                }
                """,
                """
                class Main {
                  static void main() {
                    A a = new A();
                    B b = new B();
                    a.f = b;
                  }
                }
                class A {
                  B f;
                }
                class B {
                }
                """,
                """
                class Main {
                  static void main() {
                    A a = new A();
                    B b = a.g;
                  }
                }
                class A {
                  B g;
                }
                class B {
                }
                """,
                """
                class Main {
                  static void main() {
                    A a = new A();
                  }
                }
                class A {
                }
                class B {
                }
                """
        };
        AnalysisConfig config = new AnalysisConfig(2, 2, "Main", "main");
        Program previous = SourceParser.parse(Map.of("M.java", edits[0]));
        PointerAnalysisSolver solver = new PointerAnalysisSolver(config);
        PointerAnalysisSnapshot snapshot = solver.solve(previous);

        for (int i = 1; i < edits.length; i++) {
            Program next = SourceParser.parse(Map.of("M.java", edits[i]));
            ChangeSet changeSet = ChangeSet.diff(previous, next);
            snapshot = solver.update(previous, snapshot, next, changeSet);
            PointerAnalysisSnapshot full = new PointerAnalysisSolver(config).solve(next);
            assertEquals(full.varPointsTo(), snapshot.varPointsTo(),
                    "edit " + i + " variable points-to mismatch");
            assertEquals(full.fieldPointsTo(), snapshot.fieldPointsTo(),
                    "edit " + i + " field points-to mismatch");
            previous = next;
        }
    }

    @Test
    void incrementalIsolatesUnchangedFile() {
        String libSrc = """
                class Lib {
                  static Helper make() {
                    Helper h = new Helper();
                    return h;
                  }
                }
                class Helper {
                }
                """;
        String mainV1 = """
                class Main {
                  static void main() {
                    Helper h = Lib.make();
                    A a = new A();
                  }
                }
                class A {
                }
                """;
        String mainV2 = """
                class Main {
                  static void main() {
                    Helper h = Lib.make();
                    A a = new A();
                    B b = new B();
                  }
                }
                class A {
                }
                class B {
                }
                """;
        AnalysisConfig config = AnalysisConfig.defaults();
        Program p0 = SourceParser.parse(Map.of("Lib.java", libSrc, "Main.java", mainV1));
        PointerAnalysisSolver solver = new PointerAnalysisSolver(config);
        PointerAnalysisSnapshot s0 = solver.solve(p0);

        Program p1 = SourceParser.parse(Map.of("Lib.java", libSrc, "Main.java", mainV2));
        ChangeSet changeSet = ChangeSet.diff(p0, p1);
        assertEquals(1, changeSet.changedFiles().size());
        PointerAnalysisSnapshot incremental = solver.update(p0, s0, p1, changeSet);
        PointerAnalysisSnapshot full = new PointerAnalysisSolver(config).solve(p1);
        assertEquals(full.varPointsTo(), incremental.varPointsTo());
    }

    @Test
    void changedReachableCalleeFileRePublishesNewStatements() {
        String leafV1 = """
                class Leaf {
                  Helper get() {
                    Helper h = new Helper();
                    return h;
                  }
                }
                """;
        String leafV2 = """
                class Leaf {
                  Helper get() {
                    Helper h = new Helper();
                    Extra e = new Extra();
                    return h;
                  }
                }
                class Extra {
                }
                """;
        String mainSrc = """
                class Main {
                  static void main() {
                    Leaf leaf = new Leaf();
                    Helper h = leaf.get();
                  }
                }
                class Helper {
                }
                """;
        AnalysisConfig config = new AnalysisConfig(1, 2, "Main", "main");
        Program p1 = SourceParser.parse(Map.of("Main.java", mainSrc, "Leaf.java", leafV1));
        PointerAnalysisSolver solver = new PointerAnalysisSolver(config);
        PointerAnalysisSnapshot s1 = solver.solve(p1);
        Program p2 = SourceParser.parse(Map.of("Main.java", mainSrc, "Leaf.java", leafV2));
        PointerAnalysisSnapshot incremental =
                solver.update(p1, s1, p2, ChangeSet.diff(p1, p2));
        PointerAnalysisSnapshot full = new PointerAnalysisSolver(config).solve(p2);

        assertEquals(full.varPointsTo(), incremental.varPointsTo(),
                "surviving reachability into the edited callee must re-fire and "
                        + "publish the new allocation");
        assertEquals(full.fieldPointsTo(), incremental.fieldPointsTo());
        PointerAnalysisSolver.IncrementalStats work = solver.lastIncrementalStats();
        assertTrue(work.affectedSites() >= 3);
        assertTrue(work.derivationsRegistered() > 0,
                "the edited reachable file must re-register derivations");
    }

    private String callersProgram() {
        return """
                class Main {
                  static void main() {
                    Id id = new Id();
                    A a = new A();
                    B b = new B();
                    Object pa = id.Id.id(a);
                    Object pb = id.Id.id(b);
                  }
                }
                class Id {
                  Object id(Object p) {
                    return p;
                  }
                }
                class A {
                }
                class B {
                }
                """;
    }
}
