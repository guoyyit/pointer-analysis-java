package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSolver;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.parser.SourceParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the {@code parallelism} configuration is real: it changes the
 * execution behavior of parsing (measurable speedup on a large input) while
 * keeping the result identical and deterministic.
 */
class ParallelParsingTest {

    private static final int FILES = 3000;

    private Map<String, String> manyFiles() {
        Map<String, String> sources = new LinkedHashMap<>();
        StringBuilder main = new StringBuilder("class Main {\n  static void main() {\n");
        for (int i = 0; i < FILES; i++) {
            main.append("    C").append(i).append(" c").append(i)
                    .append(" = new C").append(i).append("();\n");
        }
        main.append("  }\n}\n");
        sources.put("Main.java", main.toString());
        for (int i = 0; i < FILES; i++) {
            StringBuilder body = new StringBuilder();
            body.append("class C").append(i).append(" {\n");
            body.append("  Object f").append(i).append(";\n");
            body.append("  void m").append(i).append("(Object p) {\n");
            body.append("    Object x = p;\n");
            body.append("    this.f").append(i).append(" = x;\n");
            body.append("    Object y = this.f").append(i).append(";\n");
            body.append("    return y;\n");
            body.append("  }\n}\n");
            sources.put("C" + i + ".java", body.toString());
        }
        return sources;
    }

    @Test
    void parallelParsingIsIdenticalToSequential() {
        Map<String, String> sources = manyFiles();
        Program sequential = SourceParser.parse(sources, 1);
        for (int p : new int[]{2, 4, 8}) {
            Program parallel = SourceParser.parse(sources, p);
            assertEquals(sequential, parallel,
                    "parallelism=" + p + " must produce an identical program");
        }
    }

    @Test
    void parallelismChangesExecutionTime() {
        Map<String, String> sources = manyFiles();
        // Warm up to avoid class-loading / JIT skew.
        for (int w = 0; w < 3; w++) {
            SourceParser.parse(sources, 1);
            SourceParser.parse(sources, 8);
        }
        long bestSequential = Long.MAX_VALUE;
        long bestParallel = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long start = System.nanoTime();
            SourceParser.parse(sources, 1);
            bestSequential = Math.min(bestSequential, System.nanoTime() - start);
            start = System.nanoTime();
            SourceParser.parse(sources, 8);
            bestParallel = Math.min(bestParallel, System.nanoTime() - start);
        }
        System.out.println("PARSE sequential=" + (bestSequential / 1_000_000) + "ms"
                + " parallel=" + (bestParallel / 1_000_000) + "ms"
                + " speedup=" + String.format("%.2fx", (double) bestSequential / bestParallel));
        assertTrue(bestParallel < bestSequential,
                "parallel parsing must be measurably faster than sequential on "
                        + (FILES + 1) + " files (sequential=" + bestSequential
                        + "ns, parallel=" + bestParallel + "ns)");
    }

    @Test
    void repeatedParallelRunsAreDeterministic() {
        Map<String, String> sources = manyFiles();
        List<Program> runs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            runs.add(SourceParser.parse(sources, 8));
        }
        for (int i = 1; i < runs.size(); i++) {
            assertEquals(runs.get(0), runs.get(i),
                    "parallel run " + i + " must match the first run exactly");
        }
    }

    @Test
    void endToEndAnalysisIsIdenticalAcrossParallelism() {
        Map<String, String> sources = manyFiles();
        List<PointerAnalysisSnapshot> snapshots = new ArrayList<>();
        for (int p : new int[]{1, 2, 4, 8}) {
            AnalysisConfig config = new AnalysisConfig(1, p, "Main", "main");
            Program program = SourceParser.parse(sources, p);
            snapshots.add(new PointerAnalysisSolver(config).solve(program));
        }
        PointerAnalysisSnapshot first = snapshots.get(0);
        for (int i = 1; i < snapshots.size(); i++) {
            assertEquals(first.varPointsTo(), snapshots.get(i).varPointsTo(),
                    "parallelism=" + (i == 1 ? 2 : i == 2 ? 4 : 8)
                            + " must produce identical variable points-to sets");
            assertEquals(first.fieldPointsTo(), snapshots.get(i).fieldPointsTo(),
                    "parallelism=" + (i == 1 ? 2 : i == 2 ? 4 : 8)
                            + " must produce identical field points-to sets");
        }
    }
}
