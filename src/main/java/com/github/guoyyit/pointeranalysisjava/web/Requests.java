package com.github.guoyyit.pointeranalysisjava.web;

import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;

/**
 * HTTP request/response DTOs.
 */
public final class Requests {

    private Requests() {
    }

    public record BuildRequest(Map<String, String> sources, ConfigDto config) {
    }

    public record UpdateRequest(Map<String, String> sources) {
    }

    public record ConfigDto(Integer contextK, Integer parallelism,
                            String entryClass, String entryMethod) {
    }

    public record PointsToRequest(String context, String path) {
    }

    public record AliasRequest(String context, String left, String right) {
    }

    public record StatusDetailedResponse(long revision, int files, int methods,
                                         long contexts, int variables, int objects,
                                         int configuredParallelism, int parseParallelism,
                                         SortedSet<String> parserWorkerThreads,
                                         long parseMillis, long solveMillis,
                                         long incrementalMillis, String lastOutcome,
                                         Integer affectedSites, Long derivationsRemoved,
                                         Long derivationsRegistered) {
    }

    public record PointsToResponse(long revision,
                                   List<PointerAnalysisSnapshot.PathTarget> targets) {
    }

    public record AliasResponse(long revision, boolean mayAlias) {
    }
}
