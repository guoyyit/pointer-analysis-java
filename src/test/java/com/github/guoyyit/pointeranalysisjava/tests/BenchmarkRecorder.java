package com.github.guoyyit.pointeranalysisjava.tests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes repeatable performance-bound results to disk for later regression
 * comparison. Every run produces a timestamped JSON record plus an overwritten
 * {@code latest.json}.
 */
final class BenchmarkRecorder {

    private BenchmarkRecorder() {
    }

    static Path record(Path outputDir, String scenario, String jsonBody) {
        try {
            Files.createDirectories(outputDir);
            String stamp = java.time.Instant.now().toString().replace(':', '-');
            Path stamped = outputDir.resolve(scenario + "-" + stamp + ".json");
            Path latest = outputDir.resolve(scenario + "-latest.json");
            String payload = jsonBody.endsWith("\n") ? jsonBody : jsonBody + "\n";
            Files.writeString(stamped, payload, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.writeString(latest, payload, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return latest;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write benchmark record", e);
        }
    }
}
