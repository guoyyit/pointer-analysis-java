package com.github.guoyyit.pointeranalysisjava.tests;

import com.github.guoyyit.pointeranalysisjava.model.AccessPath;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisConfig;
import com.github.guoyyit.pointeranalysisjava.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuerySemanticsTest {

    private AnalysisService service(Path dir) {
        AnalysisConfig config = new AnalysisConfig();
        config.setContextDepth(1);
        config.setStorageDir(dir);
        return new AnalysisService(config);
    }

    @Test
    void pointsToAndFieldDereference(@TempDir Path dir) {
        AnalysisService service = service(dir);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.FIELD_FLOW));

        assertTrue(service.queryPointsTo("main", "[]", "y").allocations()
                .contains("a.lang:2@x"));
        assertTrue(service.queryPointsTo("main", "[]", "z").allocations()
                .contains("a.lang:2@x"));
        assertTrue(service.queryPointsTo("main", "[]", "y.next").allocations()
                .contains("a.lang:2@x"));
    }

    @Test
    void mayAliasPositiveAndNegative(@TempDir Path dir) {
        AnalysisService service = service(dir);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.FIELD_FLOW));

        assertTrue(service.queryAlias("main", "[]",
                AccessPath.of("x", List.of()),
                AccessPath.of("y", List.of())).mayAlias());
        assertTrue(service.queryAlias("main", "[]",
                AccessPath.of("z", List.of()),
                AccessPath.of("x", List.of())).mayAlias());
    }

    @Test
    void disjointAllocationsDoNotAlias(@TempDir Path dir) {
        AnalysisService service = service(dir);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.DISJOINT));

        assertFalse(service.queryAlias("main", "[]",
                AccessPath.of("u", List.of()),
                AccessPath.of("v", List.of())).mayAlias());
        assertTrue(service.queryAlias("main", "[]",
                AccessPath.of("u", List.of()),
                AccessPath.of("u", List.of())).mayAlias());
    }

    @Test
    void unknownVariableHasEmptyPointsTo(@TempDir Path dir) {
        AnalysisService service = service(dir);
        service.rebuild(TestFixtures.single("a.lang", TestFixtures.DISJOINT));

        assertTrue(service.queryPointsTo("main", "[]", "ghost").allocations().isEmpty());
    }
}
