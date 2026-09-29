package com.github.guoyyit.pointeranalysisjava.web;

import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.model.SourceFile;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP API for the pointer analysis service.
 */
@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    public record FilesRequest(List<SourceFile> files) {
    }

    public record IncrementRequest(List<SourceFile> files, String changeId) {
    }

    public record PointsToRequest(String method, String context, String expression) {
    }

    public record AliasRequest(String method, String context, AccessPath a, AccessPath b) {
    }

    public record SimpleMessage(String status, long revision) {
    }

    @PostMapping("/rebuild")
    public SimpleMessage rebuild(@RequestBody FilesRequest request) {
        service.rebuild(request.files());
        return new SimpleMessage("published", service.currentSnapshot().revision());
    }

    @PostMapping("/increment")
    public SimpleMessage increment(@RequestBody IncrementRequest request) {
        service.increment(new AnalysisService.SourceChange(request.files(), request.changeId()));
        return new SimpleMessage("published", service.currentSnapshot().revision());
    }

    @GetMapping("/snapshot")
    public long snapshot() {
        return service.currentSnapshot().revision();
    }

    @PostMapping("/query/points-to")
    public AnalysisService.PointsToResult pointsTo(@RequestBody PointsToRequest request) {
        return service.queryPointsTo(request.method(), request.context(), request.expression());
    }

    @PostMapping("/query/alias")
    public AnalysisService.AliasResult alias(@RequestBody AliasRequest request) {
        return service.queryAlias(request.method(), request.context(), request.a(), request.b());
    }

    @PostMapping("/verify")
    public AnalysisService.VerificationResult verify() {
        return service.verifyAgainstFullRebuild();
    }
}
