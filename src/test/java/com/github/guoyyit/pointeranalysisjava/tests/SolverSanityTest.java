package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.analysis.ChangeSet;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSolver;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.Context;
import com.github.guoyyit.pointeranalysisjava.model.Obj;
import com.github.guoyyit.pointeranalysisjava.model.Var;
import com.github.guoyyit.pointeranalysisjava.parser.SourceParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolverSanityTest {

    private static final String MAIN = """
            class Main {
              static void main() {
                A a = new A();
                a.f = new B();
                B b = a.f;
                a.set(b);
                B b2 = a.get();
              }
            }
            class A {
              B f;
              void set(B p) {
                this.f = p;
              }
              B get() {
                B r = this.f;
                return r;
              }
            }
            class B {
            }
            """;

    private Program parse(String src) {
        return SourceParser.parse(Map.of("Main.java", src));
    }

    @Test
    void basicPointsToAndFields() {
        Program program = parse(MAIN);
        PointerAnalysisSnapshot snap =
                new PointerAnalysisSolver(AnalysisConfig.defaults()).solve(program);

        Var a = new Var(Context.ROOT, "Main#main/0", "a");
        assertEquals(1, snap.pointsTo(a).size());

        List<PointerAnalysisSnapshot.PathTarget> b2 = snap.evaluatePath(Context.ROOT,
                AccessPath.local("Main#main/0", "b2"));
        assertEquals(1, b2.size());
        assertEquals(1, b2.get(0).targets().size(),
                "b2 must resolve to exactly the B allocated in main");
    }

    @Test
    void contextSensitivityDistinguishesCallers() {
        String src = """
                class Main {
                  static void main() {
                    Id id1 = new Id();
                    Id id2 = new Id();
                    A a = new A();
                    B b = new B();
                    A aa = id1.Id.identity(a);
                    B bb = id2.Id.identity(b);
                  }
                }
                class Id {
                  Object identity(Object p) {
                    return p;
                  }
                }
                class A {
                }
                class B {
                }
                """;
        Program program = SourceParser.parse(Map.of("M.java", src));
        AnalysisConfig k1 = new AnalysisConfig(1, 1, "Main", "main");
        PointerAnalysisSnapshot snap = new PointerAnalysisSolver(k1).solve(program);

        List<PointerAnalysisSnapshot.PathTarget> aa = snap.evaluatePath(Context.ROOT,
                AccessPath.local("Main#main/0", "aa"));
        List<PointerAnalysisSnapshot.PathTarget> bb = snap.evaluatePath(Context.ROOT,
                AccessPath.local("Main#main/0", "bb"));
        assertEquals(1, aa.get(0).targets().size());
        assertEquals(1, bb.get(0).targets().size());
    }

    @Test
    void incrementalMatchesFullRebuild() {
        String original = MAIN;
        String changed = MAIN.replace("B b2 = a.get();", "B b2 = a.get();\n                B extra = new B();");

        Program p0 = SourceParser.parse(Map.of("Main.java", original));
        AnalysisConfig config = AnalysisConfig.defaults();
        PointerAnalysisSolver solver = new PointerAnalysisSolver(config);
        PointerAnalysisSnapshot s0 = solver.solve(p0);

        Program p1 = SourceParser.parse(Map.of("Main.java", changed));
        ChangeSet changeSet = ChangeSet.diff(p0, p1);
        PointerAnalysisSnapshot incremental = solver.update(p0, s0, p1, changeSet);

        PointerAnalysisSnapshot full = new PointerAnalysisSolver(config).solve(p1);
        assertEquals(full.varPointsTo(), incremental.varPointsTo());
        assertEquals(full.fieldPointsTo(), incremental.fieldPointsTo());

        PointerAnalysisSolver fresh = new PointerAnalysisSolver(config);
        fresh.solve(p0);
        PointerAnalysisSnapshot incrementalFresh = fresh.update(p0, s0, p1, changeSet);
        assertEquals(full.varPointsTo(), incrementalFresh.varPointsTo());
    }
}
