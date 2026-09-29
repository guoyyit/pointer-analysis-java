package com.github.guoyyit.pointeranalysisjava.analysis;

/**
 * Analysis configuration.
 *
 * @param contextK    k-limited call-string length; 0 = context insensitive
 * @param parallelism worker threads for per-file parsing/processing
 * @param entryClass  class declaring the entry method
 * @param entryMethod entry method name (must be static)
 */
public record AnalysisConfig(int contextK, int parallelism, String entryClass,
                             String entryMethod) {

    public AnalysisConfig {
        if (contextK < 0) {
            throw new IllegalArgumentException("contextK must be >= 0");
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
    }

    public static AnalysisConfig defaults() {
        return new AnalysisConfig(1, Math.max(1, Runtime.getRuntime().availableProcessors()),
                "Main", "main");
    }
}
