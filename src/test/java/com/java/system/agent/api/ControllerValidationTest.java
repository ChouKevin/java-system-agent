package com.java.system.agent.api;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.git.service.GitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControllerValidationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GitService gitService = null;
        AnalysisService analysisService = null;

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new RepoController(gitService, analysisService),
                        new CallGraphController(analysisService),
                        new AnalysisController(analysisService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void callGraphShouldReturnBadRequestWhenBodyIsInvalid() throws Exception {
        String invalidBody = """
                {
                  "packageName": "",
                  "className": "VipService",
                  "methodSignature": "getVip"
                }
                """;

        mockMvc.perform(post("/analysis/call-graph/test-repo")
                        .contentType("application/json")
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request body validation failed"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("packageName")));
    }

    @Test
    void checkoutShouldReturnBadRequestWhenBranchIsMissing() throws Exception {
        mockMvc.perform(post("/git/checkout-repo/test-repo")
                        .param("unused", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Missing required request parameter"));
    }
}
