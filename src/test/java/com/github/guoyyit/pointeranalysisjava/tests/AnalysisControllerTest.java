package com.github.guoyyit.pointeranalysisjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "pointer-analysis.storage-dir=target/controller-test-data-${random.uuid}")
@AutoConfigureMockMvc
class AnalysisControllerTest {

    @Autowired
    private MockMvc mvc;

    private static final String SOURCE = """
            class Main {
              static void main() {
                A a = new A();
                B b = new B();
              }
            }
            class A {
            }
            class B {
            }
            """;

    @Test
    void fullWorkflowOverHttp() throws Exception {
        String build = "{\"sources\":{\"Main.java\":" + quote(SOURCE)
                + "},\"config\":{\"contextK\":1}}";
        mvc.perform(post("/api/build").contentType(MediaType.APPLICATION_JSON).content(build))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.objects").value(2));

        mvc.perform(post("/api/query/points-to")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":\"<root>\",\"path\":\"Main#main/0#a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("points-to"))
                .andExpect(jsonPath("$.answer").exists());

        mvc.perform(post("/api/query/alias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":\"<root>\",\"left\":\"Main#main/0#a\","
                                + "\"right\":\"Main#main/0#b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("false"));

        mvc.perform(get("/api/status")).andExpect(status().isOk());
        mvc.perform(get("/api/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private static String quote(String text) {
        StringBuilder builder = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(c);
            }
        }
        return builder.append('"').toString();
    }
}
