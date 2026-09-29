package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.analysis.ChangeSet;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSolver;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.parser.SourceParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A change confined to one small file must cost time proportional to its
 * affected region, not to the total size of the code base. Disabled by default
 * (timing-sensitive); run with {@code -Drun.perf=true}.
 */
@EnabledIfSystemProperty(named = "run.perf", matches = "true")
class PerformanceBoundTest {

    private static final int CLASSES = 2500;

    private String leafFile(boolean extra) {
        StringBuilder leaf = new StringBuilder();
        leaf.append("class Leaf {\n  static Helper get() {\n    Helper h = new Helper();\n");
        if (extra) {
            leaf.append("    Extra e = new Extra();\n");
        }
        leaf.append("    return h;\n  }\n}\n");
        if (extra) {
            leaf.append("class Extra {\n}\n");
        }
        return leaf.toString();
    }

    private Map<String, String> largeCodebase(boolean extraClass) {
        Map<String, String> sources = new LinkedHashMap<>();
        StringBuilder main = new StringBuilder();
        main.append("class Main {\n  static void main() {\n");
        main.append("    Helper h = Lib.make();\n");
        main.append("  }\n}\n");
        for (int i = 0; i < CLASSES; i++) {
            main.append("class H").append(i).append(" {\n}\n");
        }
        sources.put("Main.java", main.toString());

        StringBuilder lib = new StringBuilder();
        lib.append("class Lib {\n  static Helper make() {\n    Helper h = new Helper();\n");
        for (int i = 0; i < CLASSES; i++) {
            lib.append("    W").append(i).append(" w").append(i)
                    .append(" = new W").append(i).append("();\n");
        }
        lib.append("    return h;\n  }\n}\n");
        for (int i = 0; i < CLASSES; i++) {
            lib.append("class W").append(i).append(" {\n  Helper h;\n}\n");
        }
        lib.append("class Helper {\n}\n");
        sources.put("Lib.java", lib.toString());
        sources.put("Leaf.java", leafFile(extraClass));
        return sources;
    }

    @Test
    void oneFileIncrementalIsMuchCheaperThanFullRebuild() {
        Map<String, String> v1 = largeCodebase(false);
        Map<String, String> v2 = largeCodebase(true);

        AnalysisConfig config = new AnalysisConfig(1, 4, "Main", "main");
        Program p1 = SourceParser.parse(v1);
        PointerAnalysisSolver solver = new PointerAnalysisSolver(config);
        PointerAnalysisSnapshot s1 = solver.solve(p1);

        long fullStart = System.nanoTime();
        Program p2 = SourceParser.parse(v2);
        PointerAnalysisSnapshot full = new PointerAnalysisSolver(config).solve(p2);
        long fullMillis = (System.nanoTime() - fullStart) / 1_000_000;

        PointerAnalysisSolver incrementalSolver = new PointerAnalysisSolver(config);
        incrementalSolver.solve(p1);
        ChangeSet changeSet = ChangeSet.diff(p1, p2);
        long incrStart = System.nanoTime();
        PointerAnalysisSnapshot incremental = incrementalSolver.update(p1, s1, p2, changeSet);
        long incrMillis = (System.nanoTime() - incrStart) / 1_000_000;

        assertEquals(full.varPointsTo(), incremental.varPointsTo());
        assertEquals(full.fieldPointsTo(), incremental.fieldPointsTo());

        System.out.println("PERF full=" + fullMillis + "ms incremental=" + incrMillis + "ms");
        assertTrue(incrMillis * 5 <= Math.max(fullMillis, 1),
                "incremental must not be slower than a full rebuild ("
                        + incrMillis + " vs " + fullMillis + " ms)");
    }
}
