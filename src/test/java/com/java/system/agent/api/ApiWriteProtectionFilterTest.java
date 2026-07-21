package com.java.system.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.git.service.GitService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiWriteProtectionFilterTest {

    private MockMvc buildMockMvc(String configuredToken) {
        GitService gitService = mock(GitService.class);
        AnalysisService analysisService = mock(AnalysisService.class);
        when(gitService.pullRepository("demo-repo")).thenReturn("pulled");
        when(analysisService.allRepos()).thenReturn(List.of());
        when(analysisService.reloadRepoAfter(eq("demo-repo"), any()))
                .thenAnswer(invocation -> invocation.<Supplier<String>>getArgument(1).get());

        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        ApiWriteProtectionFilter filter = new ApiWriteProtectionFilter(
                new ApiSecurityProperties(configuredToken), objectMapper);

        return MockMvcBuilders
                .standaloneSetup(new RepoController(gitService, analysisService))
                .addFilter(filter, "/git/*")
                .build();
    }

    @Test
    void should_return_403_when_write_token_is_not_configured() throws Exception {
        MockMvc mockMvc = buildMockMvc("");

        mockMvc.perform(post("/git/pull-repo/demo-repo")
                        .header(ApiWriteProtectionFilter.API_TOKEN_HEADER, "anything"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Git write endpoints are disabled"))
                .andExpect(jsonPath("$.path").value("/git/pull-repo/demo-repo"))
                .andExpect(jsonPath("$.details[0]").value(containsString("API_WRITE_TOKEN")));
    }

    @Test
    void should_return_401_when_token_header_is_missing() throws Exception {
        MockMvc mockMvc = buildMockMvc("s3cret");

        mockMvc.perform(post("/git/pull-repo/demo-repo"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.details[0]").value(containsString("X-Api-Token")));
    }

    @Test
    void should_return_401_when_token_does_not_match() throws Exception {
        MockMvc mockMvc = buildMockMvc("s3cret");

        mockMvc.perform(post("/git/pull-repo/demo-repo")
                        .header(ApiWriteProtectionFilter.API_TOKEN_HEADER, "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void should_pass_through_when_token_matches() throws Exception {
        MockMvc mockMvc = buildMockMvc("s3cret");

        mockMvc.perform(post("/git/pull-repo/demo-repo")
                        .header(ApiWriteProtectionFilter.API_TOKEN_HEADER, "s3cret"))
                .andExpect(status().isOk())
                .andExpect(content().string("pulled - Cache reloaded"));
    }

    @Test
    void should_allow_read_only_git_endpoint_without_token() throws Exception {
        MockMvc mockMvc = buildMockMvc("s3cret");

        mockMvc.perform(get("/git/repos"))
                .andExpect(status().isOk());
    }
}
