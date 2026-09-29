package com.github.guoyyit.pointeranalysisjava.config;

import com.github.guoyyit.pointeranalysisjava.persistence.RevisionStore;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires persistence and the analysis service as Spring beans. The storage
 * directory is configurable via {@code pointer-analysis.storage-dir}.
 */
@Configuration
public class InfrastructureConfig {

    @Bean
    public RevisionStore revisionStore(
            @Value("${pointer-analysis.storage-dir:./pointer-analysis-data}") String storageDir) {
        return new RevisionStore(Path.of(storageDir));
    }

    @Bean
    public AnalysisService analysisService(RevisionStore revisionStore) {
        return new AnalysisService(revisionStore);
    }
}
