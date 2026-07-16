package com.java.system.agent.api;

import com.java.system.agent.analysis.exception.UnknownRepoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {

        @GetMapping("/boom/unknown-repo")
        String unknownRepo() {
            throw new UnknownRepoException("ghost-repo");
        }

        @GetMapping("/boom/internal")
        String internal() {
            throw new IllegalStateException("repos/secret/path must not leak");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void should_return_404_error_response_when_repo_is_unknown() throws Exception {
        mockMvc.perform(get("/boom/unknown-repo"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Repository not found"))
                .andExpect(jsonPath("$.path").value("/boom/unknown-repo"))
                .andExpect(jsonPath("$.details[0]").value("Unknown repository: ghost-repo"));
    }

    @Test
    void should_return_generic_500_error_response_without_internal_details() throws Exception {
        mockMvc.perform(get("/boom/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("Unexpected server error"))
                .andExpect(jsonPath("$.details").isEmpty());
    }
}
