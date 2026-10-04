package com.github.guoyyit.pointeranalysisjava.web;

import com.github.guoyyit.pointeranalysisjava.analysis.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.Context;
import com.github.guoyyit.pointeranalysisjava.persistence.QueryRecord;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import com.github.guoyyit.pointeranalysisjava.service.RevisionView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.guoyyit.pointeranalysisjava.web.Requests.AliasRequest;
import static com.github.guoyyit.pointeranalysisjava.web.Requests.BuildRequest;
import static com.github.guoyyit.pointeranalysisjava.web.Requests.PointsToRequest;
import static com.github.guoyyit.pointeranalysisjava.web.Requests.UpdateRequest;

/**
 * REST facade for the pointer/alias analysis service.
 *
 * <ul>
 *   <li>{@code POST /api/build}   - full analysis from source files</li>
 *   <li>{@code POST /api/update}  - file-level incremental update</li>
 *   <li>{@code POST /api/rebuild} - full rebuild</li>
 *   <li>{@code POST /api/verify}  - cross-check incremental vs full rebuild</li>
 *   <li>{@code POST /api/query/points-to} and {@code /api/query/alias}</li>
 *   <li>{@code GET  /api/status}, {@code GET /api/history}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public final class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @PostMapping("/build")
    public Requests.StatusDetailedResponse build(@RequestBody BuildRequest request) {
        AnalysisConfig config = resolveConfig(request.config());
        RevisionView view = service.build(request.sources(), config);
        return detailedStatus(view);
    }

    @PostMapping("/update")
    public Requests.StatusDetailedResponse update(@RequestBody UpdateRequest request) {
        return detailedStatus(service.update(request.sources()));
    }

    @PostMapping("/rebuild")
    public Requests.StatusDetailedResponse rebuild(@RequestBody UpdateRequest request) {
        return detailedStatus(service.rebuild(request.sources()));
    }

    @PostMapping("/verify")
    public AnalysisService.VerificationReport verify(@RequestBody UpdateRequest request) {
        return service.verifyIncremental(request.sources());
    }

    @PostMapping("/query/points-to")
    public QueryRecord pointsTo(@RequestBody PointsToRequest request) {
        return service.queryPointsTo(parseContext(request.context()),
                parsePath(request.path()));
    }

    @PostMapping("/query/alias")
    public QueryRecord alias(@RequestBody AliasRequest request) {
        return service.queryAlias(parseContext(request.context()),
                parsePath(request.left()), parsePath(request.right()));
    }

    @GetMapping("/status")
    public ResponseEntity<Requests.StatusDetailedResponse> status() {
        if (!service.isReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(detailedStatus(service.currentView()));
    }

    private Requests.StatusDetailedResponse detailedStatus(RevisionView view) {
        AnalysisService.ExecutionStats stats = service.executionStats();
        long contexts = view.snapshot().varPointsTo().keySet().stream()
                .map(v -> v.context())
                .distinct()
                .count();
        int variables = view.snapshot().varPointsTo().size();
        int objects = countObjects(view);
        return new Requests.StatusDetailedResponse(
                view.revision(),
                view.snapshot().program().sourceHashByFile().size(),
                view.snapshot().program().allMethods().size(),
                contexts, variables, objects,
                stats.configuredParallelism(), stats.parseParallelism(),
                stats.parserWorkerThreads(), stats.parseMillis(), stats.lastSolveMillis(),
                stats.lastIncrementalMillis(), stats.lastOutcome(),
                stats.affectedSites(), stats.derivationsRemoved(),
                stats.derivationsRegistered());
    }

    @GetMapping("/history")
    public List<QueryRecord> history() {
        return service.history();
    }

    private AnalysisConfig resolveConfig(Requests.ConfigDto dto) {
        if (dto == null) {
            return AnalysisConfig.defaults();
        }
        AnalysisConfig defaults = AnalysisConfig.defaults();
        return new AnalysisConfig(
                dto.contextK() != null ? dto.contextK() : defaults.contextK(),
                dto.parallelism() != null ? dto.parallelism() : defaults.parallelism(),
                dto.entryClass() != null ? dto.entryClass() : defaults.entryClass(),
                dto.entryMethod() != null ? dto.entryMethod() : defaults.entryMethod());
    }

    private int countObjects(RevisionView view) {
        return (int) view.snapshot().varPointsTo().values().stream()
                .flatMap(java.util.Set::stream)
                .distinct()
                .count();
    }

    static AccessPath parsePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        String text = raw.trim();
        String method = null;
        int hash = text.lastIndexOf('#');
        if (hash >= 0 && text.substring(0, hash).contains("#")) {
            method = text.substring(0, hash);
            text = text.substring(hash + 1);
        }
        String[] parts = text.split("\\.");
        if (parts.length == 0 || parts[0].isBlank()) {
            throw new IllegalArgumentException("invalid access path: " + raw);
        }
        AccessPath path = AccessPath.local(method, parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isBlank()) {
                throw new IllegalArgumentException("invalid access path: " + raw);
            }
            path = path.append(parts[i]);
        }
        return path;
    }

    static Context parseContext(String raw) {
        if (raw == null || raw.isBlank() || "*".equals(raw.trim())
                || "<root>".equals(raw.trim())) {
            return null;
        }
        List<String> sites = new ArrayList<>();
        for (String part : raw.trim().split("/")) {
            if (!part.isBlank()) {
                sites.add(part);
            }
        }
        return Context.of(sites);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleNotReady(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
