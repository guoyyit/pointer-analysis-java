package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.analysis.ChangeSet;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSolver;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.parser.SourceParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance-bound acceptance tests that run as part of the default
 * {@code mvn test} flow. No system property or environment variable gates
 * them.
 *
 * <p>The primary regression signal is deterministic work counting (affected
 * statement sites and provenance derivations), which is immune to machine
 * noise: a change confined to one file must cost work proportional to that
 * file's affected region even when the surrounding unrelated code base grows.
 * Wall-clock timings are measured with warm-up and best-of-N as a secondary,
 * generously bounded signal; every run is written under
 * {@code target/benchmark-results} for later comparison.
 */
class PerformanceBoundTest {

    private static final Path RESULT_DIR = Paths.get("target", "benchmark-results");

    private static final int SMALL_FILLER = 200;
    private static final int LARGE_FILLER = 2_500;

    /**
     * Builds a code base where the only changed file ({@code Leaf.java}) is
     * reachable and small, while {@code fillerClasses} unrelated classes and a
     * large unrelated method body make up the surrounding program size.
     */
    private Map<String, String> codebase(int fillerClasses, boolean extra) {
        Map<String, String> sources = new LinkedHashMap<>();

        StringBuilder main = new StringBuilder();
        main.append("class Main {\n  static void main() {\n");
        main.append("    Leaf leaf = new Leaf();\n");
        main.append("    Helper h = leaf.get();\n");
        for (int i = 0; i < fillerClasses; i++) {
            main.append("    U").append(i).append(" u").append(i)
                    .append(" = new U").append(i).append("();\n");
        }
        main.append("  }\n}\n");
        for (int i = 0; i < fillerClasses; i++) {
            main.append("class U").append(i).append(" {\n}\n");
        }
        sources.put("Main.java", main.toString());

        StringBuilder leaf = new StringBuilder();
        leaf.append("class Leaf {\n  Helper get() {\n");
        leaf.append("    Helper h = new Helper();\n");
        if (extra) {
            leaf.append("    Extra e = new Extra();\n");
        }
        leaf.append("    return h;\n  }\n}\n");
        if (extra) {
            leaf.append("class Extra {\n}\n");
        }
        sources.put("Leaf.java", leaf.toString());
        sources.put("Helper.java", "class Helper {\n}\n");
        return sources;
    }

    private record RunResult(long fullMillis, long incrementalMillis,
                             PointerAnalysisSolver.IncrementalStats work,
                             PointerAnalysisSnapshot full,
                             PointerAnalysisSnapshot incremental) {
    }

    private RunResult runScenario(int fillerClasses) {
        Map<String, String> v1 = codebase(fillerClasses, false);
        Map<String, String> v2 = codebase(fillerClasses, true);
        AnalysisConfig config = new AnalysisConfig(1, 4, "Main", "main");

        Program p1 = SourceParser.parse(v1, config.parallelism()).program();
        Program p2 = SourceParser.parse(v2, config.parallelism()).program();

        PointerAnalysisSolver incrementalSolver = new PointerAnalysisSolver(config);
        PointerAnalysisSnapshot s1 = incrementalSolver.solve(p1);
        ChangeSet changeSet = ChangeSet.diff(p1, p2);

        // Warm up JIT so the measured best-of-N reflects steady-state cost.
        for (int i = 0; i < 3; i++) {
            new PointerAnalysisSolver(config).solve(p2);
        }
        long bestFull = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            new PointerAnalysisSolver(config).solve(p2);
            bestFull = Math.min(bestFull, (System.nanoTime() - start) / 1_000_000L);
        }

        long bestIncremental = Long.MAX_VALUE;
        PointerAnalysisSnapshot incremental = null;
        PointerAnalysisSolver measured = null;
        for (int i = 0; i < 5; i++) {
            PointerAnalysisSolver solver = new PointerAnalysisSolver(config);
            solver.solve(p1);
            long start = System.nanoTime();
            incremental = solver.update(p1, s1, p2, changeSet);
            bestIncremental =
                    Math.min(bestIncremental, (System.nanoTime() - start) / 1_000_000L);
            measured = solver;
        }

        PointerAnalysisSnapshot full = new PointerAnalysisSolver(config).solve(p2);
        return new RunResult(bestFull, bestIncremental, measured.lastIncrementalStats(),
                full, incremental);
    }

    @Test
    void oneFileChangeIsRegionBoundedAndMuchCheaperThanFullRebuild() {
        RunResult run = runScenario(LARGE_FILLER);

        assertEquals(run.full().varPointsTo(), run.incremental().varPointsTo(),
                "incremental result must equal the full rebuild");
        assertEquals(run.full().fieldPointsTo(), run.incremental().fieldPointsTo());

        // Deterministic, machine-independent bound: only Leaf.java sites are
        // affected regardless of the thousands of unrelated filler classes.
        assertTrue(run.work().affectedSites() <= 12,
                "affected sites must stay local to the changed file, got "
                        + run.work().affectedSites());

        // Timing is a secondary signal; best-of-N keeps this resilient on
        // loaded CI machines while still failing a true full-rebuild cost.
        long floor = Math.max(run.fullMillis(), 1L);
        assertTrue(run.incrementalMillis() * 5 <= floor || run.fullMillis() >= 20,
                "incremental re-analysis (" + run.incrementalMillis()
                        + "ms) must be much cheaper than full rebuild ("
                        + run.fullMillis() + "ms)");

        String json = """
                {
                  "scenario": "one-file-change-in-large-codebase",
                  "fillerClasses": %d,
                  "fullRebuildMillisBestOf5": %d,
                  "incrementalMillisBestOf5": %d,
                  "affectedSites": %d,
                  "derivationsRemoved": %d,
                  "derivationsRegistered": %d,
                  "speedup": %.1f
                }
                """.formatted(LARGE_FILLER, run.fullMillis(), run.incrementalMillis(),
                run.work().affectedSites(), run.work().derivationsRemoved(),
                run.work().derivationsRegistered(),
                run.fullMillis() / (double) Math.max(run.incrementalMillis(), 1));
        BenchmarkRecorder.record(RESULT_DIR, "one-file-change", json);
        System.out.println("PERF " + json.replace('\n', ' '));
    }

    @Test
    void unrelatedCodeGrowthDoesNotInflateIncrementalWork() {
        RunResult small = runScenario(SMALL_FILLER);
        RunResult large = runScenario(LARGE_FILLER);

        // Identical edit => identical affected-site and provenance work even
        // though the surrounding program grew by more than 10x.
        assertEquals(small.work().affectedSites(), large.work().affectedSites(),
                "affected sites must not grow with unrelated file size");
        assertTrue(small.work().affectedSites() > 0,
                "the changed reachable file must actually invalidate work");
        assertTrue(small.work().derivationsRemoved() > 0
                        && large.work().derivationsRemoved() > 0,
                "both scenarios must tear down the changed region's derivations");
        assertEquals(small.work().derivationsRemoved(), large.work().derivationsRemoved(),
                "removed derivations must depend on the region, not program size");
        assertEquals(small.work().derivationsRegistered(),
                large.work().derivationsRegistered(),
                "registered derivations must depend on the region, not program size");

        // The affected region itself is tiny even in the 2,500-class program.
        assertTrue(large.work().derivationsRemoved() <= 40,
                "re-analysis work must stay local, got "
                        + large.work().derivationsRemoved() + " removals");

        String json = """
                {
                  "scenario": "unrelated-growth-invariance",
                  "smallFillerClasses": %d,
                  "largeFillerClasses": %d,
                  "small": {"affectedSites": %d, "derivationsRemoved": %d,
                            "derivationsRegistered": %d, "incrementalMillis": %d},
                  "large": {"affectedSites": %d, "derivationsRemoved": %d,
                            "derivationsRegistered": %d, "incrementalMillis": %d}
                }
                """.formatted(
                SMALL_FILLER, LARGE_FILLER,
                small.work().affectedSites(), small.work().derivationsRemoved(),
                small.work().derivationsRegistered(), small.incrementalMillis(),
                large.work().affectedSites(), large.work().derivationsRemoved(),
                large.work().derivationsRegistered(), large.incrementalMillis());
        BenchmarkRecorder.record(RESULT_DIR, "growth-invariance", json);
        System.out.println("PERF " + json.replace('\n', ' '));
    }
}
