package com.github.guoyyit.pointeranalysisjava.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Configuration for the pointer analysis service.
 */
@Component
@ConfigurationProperties(prefix = "pointer-analysis")
public class AnalysisConfig {

    private int contextDepth = 1;
    private int parallelism = 1;
    private Path storageDir = Path.of(System.getProperty("java.io.tmpdir"),
            "pointer-analysis-java");

    public int getContextDepth() {
        return contextDepth;
    }

    public void setContextDepth(int contextDepth) {
        this.contextDepth = contextDepth;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public Path getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(Path storageDir) {
        this.storageDir = storageDir;
    }
}
