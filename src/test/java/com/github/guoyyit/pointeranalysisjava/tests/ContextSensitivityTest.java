package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.model.SourceFile;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContextSensitivityTest {

    private AnalysisService service(Path dir, int depth) {
        AnalysisConfig config = new AnalysisConfig();
        config.setContextDepth(depth);
        config.setStorageDir(dir);
        return new AnalysisService(config);
    }

    @Test
    void sameLocalIsDistinguishedByCallSite(@TempDir Path dir) {
        AnalysisService service = service(dir, 1);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));

        AnalysisService.PointsToResult first =
                service.queryPointsTo("get", "[a.lang:4]", "p");
        AnalysisService.PointsToResult second =
                service.queryPointsTo("get", "[a.lang:5]", "p");

        assertEquals(List.of("a.lang:2@a"), first.allocations());
        assertEquals(List.of("a.lang:3@b"), second.allocations());
        assertNotEquals(first.context(), second.context());
        assertEquals("[a.lang:4]", first.context());
    }

    @Test
    void callResultsFollowTheirContext(@TempDir Path dir) {
        AnalysisService service = service(dir, 1);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));

        assertEquals(List.of("a.lang:2@a"),
                service.queryPointsTo("main", "[]", "x").allocations());
        assertEquals(List.of("a.lang:3@b"),
                service.queryPointsTo("main", "[]", "y").allocations());
    }

    @Test
    void depthZeroMergesContexts(@TempDir Path dir) {
        AnalysisService service = service(dir, 0);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));

        AnalysisService.PointsToResult merged =
                service.queryPointsTo("get", "[]", "p");
        assertEquals(List.of("a.lang:2@a", "a.lang:3@b"), merged.allocations());
    }

    @Test
    void answersReportTheirContext(@TempDir Path dir) {
        AnalysisService service = service(dir, 2);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.TWO_CALL_SITES));

        AnalysisService.PointsToResult result =
                service.queryPointsTo("get", "[a.lang:4]", "q");
        assertEquals("[a.lang:4]", result.context());
        assertEquals(1, result.revision());
    }
}
