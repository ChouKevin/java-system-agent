package com.java.system.agent.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.service.AgentAiService;
import com.java.system.agent.ai.tools.DocumentTools;
import com.java.system.agent.analysis.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAiServiceTest {

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private DocumentTools documentTools;

    @Mock
    private AnalysisService analysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void analyzeWithTools_emitsOuterLlmChunks() {
        ChatClient.Builder builder = mockBuilder(List.of("業務回覆"));
        AgentAiService service = new AgentAiService(builder, chatMemory, documentTools, analysisService, objectMapper);

        Flux<String> result = service.analyzeWithTools("thread-1", "如何計算獎金?");

        List<String> items = result.collectList().block();
        assertThat(items).containsExactly("業務回覆");
    }

    @Test
    void analyzeWithTools_completesAfterOuterStreamEnds() {
        ChatClient.Builder builder = mockBuilder(List.of("part1", "part2"));
        AgentAiService service = new AgentAiService(builder, chatMemory, documentTools, analysisService, objectMapper);

        Flux<String> result = service.analyzeWithTools("thread-1", "test query");

        List<String> items = result.collectList().block();
        assertThat(items).containsExactly("part1", "part2");
    }

    @Test
    void analyzeWithTools_mergesInnerSinkIntoOutput() {
        ChatClient.Builder builder = mockBuilder(List.of("outer"));
        AgentAiService service = new AgentAiService(builder, chatMemory, documentTools, analysisService, objectMapper);

        Flux<String> result = service.analyzeWithTools("thread-1", "test");

        List<String> items = result.collectList().block();
        assertThat(items).contains("outer");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ChatClient.Builder mockBuilder(List<String> outerChunks) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(builder.defaultAdvisors(any(Advisor[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.toolContext(any(Map.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(any(Advisor[].class))).thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.fromIterable(outerChunks));

        return builder;
    }
}
