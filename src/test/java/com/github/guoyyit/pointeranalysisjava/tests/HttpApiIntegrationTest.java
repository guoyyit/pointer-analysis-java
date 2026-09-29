package com.github.guoyyit.pointeranalysisjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "pointer-analysis.storage-dir=${java.io.tmpdir}/pa-http-it")
@AutoConfigureMockMvc
class HttpApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rebuildIncrementQueryAndVerifyOverHttp() throws Exception {
        String source = TestFixtures.TWO_CALL_SITES.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n");
        String body = "{\"files\":[{\"path\":\"a.lang\",\"content\":\"" + source + "\"}]}";

        mockMvc.perform(post("/api/rebuild").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        String pointsTo = "{\"method\":\"get\",\"context\":\"[a.lang:4]\",\"expression\":\"p\"}";
        mockMvc.perform(post("/api/query/points-to")
                        .contentType("application/json").content(pointsTo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocations[0]").value("a.lang:2@a"));

        String alias = "{\"method\":\"main\",\"context\":\"[]\",\"a\":{\"base\":\"x\",\"fields\":[]},"
                + "\"b\":{\"base\":\"x\",\"fields\":[]}}";
        mockMvc.perform(post("/api/query/alias")
                        .contentType("application/json").content(alias))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayAlias").value(true));

        String changedSource = TestFixtures.TWO_CALL_SITES
                .replace("call y = get(b);", "call y = get(a);")
                .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        String increment = "{\"changeId\":\"http-c1\",\"files\":[{\"path\":\"a.lang\","
                + "\"content\":\"" + changedSource + "\"}]}";
        mockMvc.perform(post("/api/increment")
                        .contentType("application/json").content(increment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));

        mockMvc.perform(post("/api/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));

        mockMvc.perform(get("/api/snapshot"))
                .andExpect(status().isOk());
    }
}
