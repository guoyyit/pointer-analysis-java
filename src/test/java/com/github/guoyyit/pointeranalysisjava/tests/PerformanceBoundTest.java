package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.analysis.ChangeSet;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSolver;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.parser.SourceParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance-bound verification, part of the default test suite (never
 * skipped). Covers:
 * <ul>
 *   <li>parallel parsing is measurably faster than sequential parsing on a
 *       large input (the {@code parallelism} configuration is real);</li>
 *   <li>a single-file incremental reanalysis costs time proportional to the
 *       affected region, not to the total file count;</li>
 *   <li>incremental results equal a full rebuild.</li>
 * </ul>
 * Results are written to {@code target/perf-results.txt} for regression
 * comparison. Timing-sensitive: assertions use generous bounds and the best of
 * several runs to avoid flakiness.
 */
class PerformanceBoundTest {

    private static final int CLASSES = 2500;
    private static final Path RESULTS = Path.of("target", "perf-results.txt");

    private Map<String, String> largeCodebase(boolean extraClass) {
        Map<String, String> sources = new LinkedHashMap<>();
        StringBuilder main = new StringBuilder();
        main.append("class Main {\n  static void main() {\n");
        main.append("    Helper h = Lib.make();\n");
        main.append("  }\n}\n");
        sources.put("Main.java", main.toString());

        StringBuilder lib = new StringBuilder();
        lib.append("class Lib {\n  static Helper make() {\n    Helper h = new Helper();\n");
        for (int i = 0; i < CLASSES; i++) {
            lib.append("    W").append(i).append(" w").append(i)
                    .append(" = new W").append(i).append("();\n");
        }
        lib.append("    return h;\n  }\n}\n");
        sources.put("Lib.java", lib.toString());

        for (int i = 0; i < CLASSES; i++) {
            StringBuilder w = new StringBuilder();
            w.append("class W").append(i).append(" {\n  Helper h;\n}\n");
            sources.put("W" + i + ".java", w.toString());
        }
        sources.put("Helper.java", "class Helper {\n}\n");
        sources.put("Leaf.java", leafFile(extraClass));
        return sources;
    }

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

    private long bestOf(int runs, Runnable task) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            task.run();
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }

    @Test
    void parallelParsingIsFasterThanSequential() {
        Map<String, String> sources = largeCodebase(false);
        // Warm up to avoid class-loading / JIT skew.
        bestOf(3, () -> SourceParser.parse(sources, 1));
        bestOf(3, () -> SourceParser.parse(sources, 4));

        long sequential = bestOf(5, () -> SourceParser.parse(sources, 1));
        long parallel = bestOf(5, () -> SourceParser.parse(sources, 4));

        long sequentialMs = sequential / 1_000_000;
        long parallelMs = parallel / 1_000_000;
        double speedup = (double) sequential / parallel;
        System.out.println("PERF parse sequential=" + sequentialMs + "ms"
                + " parallel=" + parallelMs + "ms speedup=" + String.format("%.2fx", speedup));
        appendResult("parse_sequential_ms", sequentialMs);
        appendResult("parse_parallel_ms", parallelMs);
        appendResult("parse_speedup", String.format("%.2f", speedup));

        Program p1 = SourceParser.parse(sources, 1);
        Program p4 = SourceParser.parse(sources, 4);
        assertEquals(p1, p4, "parallel parsing must produce an identical program");
        assertTrue(parallel < sequential,
                "parallel parsing must be faster than sequential on " + sources.size()
                        + " files (sequential=" + sequential + "ns, parallel=" + parallel + "ns)");
    }

    @Test
    void oneFileIncrementalIsMuchCheaperThanFullRebuild() {
        Map<String, String> v1 = largeCodebase(false);
        Map<String, String> v2 = largeCodebase(true);

        AnalysisConfig config = new AnalysisConfig(1, 1, "Main", "main");
        Program p1 = SourceParser.parse(v1, 1);
        Program p2 = SourceParser.parse(v2, 1);
        ChangeSet changeSet = ChangeSet.diff(p1, p2);

        // Full rebuild cost (fresh solver each run).
        bestOf(3, () -> new PointerAnalysisSolver(config).solve(p2));
        long fullNanos = bestOf(5, () -> new PointerAnalysisSolver(config).solve(p2));
        long fullMillis = fullNanos / 1_000_000;

        // Reanalysis cost: solve p1 once, then measure only the update.
        PointerAnalysisSolver solver = new PointerAnalysisSolver(config);
        PointerAnalysisSnapshot s1 = solver.solve(p1);
        // Warm up the update path, then reset the engine to the p1 state.
        solver.update(p1, s1, p2, changeSet);
        solver.solve(p1);
        long incrStart = System.nanoTime();
        solver.update(p1, s1, p2, changeSet);
        long incrNanos = System.nanoTime() - incrStart;
        long incrMillis = incrNanos / 1_000_000;

        // Verify incremental equals full rebuild.
        PointerAnalysisSnapshot full = new PointerAnalysisSolver(config).solve(p2);
        PointerAnalysisSolver verifySolver = new PointerAnalysisSolver(config);
        verifySolver.solve(p1);
        PointerAnalysisSnapshot incremental = verifySolver.update(p1, s1, p2, changeSet);

        assertEquals(full.varPointsTo(), incremental.varPointsTo());
        assertEquals(full.fieldPointsTo(), incremental.fieldPointsTo());

        double speedup = (double) fullNanos / incrNanos;
        System.out.println("PERF full=" + fullMillis + "ms incremental=" + incrMillis + "ms"
                + " speedup=" + String.format("%.2fx", speedup));
        appendResult("full_rebuild_ms", fullMillis);
        appendResult("incremental_ms", incrMillis);
        appendResult("incremental_speedup", String.format("%.2f", speedup));

        assertTrue(incrMillis * 5 <= Math.max(fullMillis, 1),
                "incremental must not be slower than a full rebuild ("
                        + incrMillis + " vs " + fullMillis + " ms)");
    }

    private static void appendResult(String key, Object value) {
        try {
            Files.createDirectories(RESULTS.getParent());
            String line = key + "=" + value + "\n";
            boolean exists = Files.exists(RESULTS);
            Files.writeString(RESULTS, line, StandardCharsets.UTF_8,
                    exists ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
        } catch (IOException e) {
            System.out.println("PERF could not write results file: " + e.getMessage());
        }
    }
}
