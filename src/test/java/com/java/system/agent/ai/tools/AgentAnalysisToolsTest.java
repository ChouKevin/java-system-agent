package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.AnalysisErrorCode;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAnalysisToolsTest {

    @Mock
    private AnalysisService analysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void findCallGraph_callsAnalysisService_withCorrectArgs() {
        FlattenedCallGraph callGraph = FlattenedCallGraph.builder()
                .rootSignature("calculate").methods(List.of()).build();
        when(analysisService.analyzeMethodStructured(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(AnalysisResult.success(callGraph, null));
        AgentAnalysisTools tools = new AgentAnalysisTools(
                analysisService, mockStreamingClient(List.of("說明")), objectMapper);

        tools.findCallGraph("test-repo",
                "com.example.service", "MainService", "calculate", emptyToolContext());

        verify(analysisService).analyzeMethodStructured(
                "test-repo", "com.example.service", "MainService", "calculate");
    }

    @Test
    void findCallGraph_returnsConcatenatedInnerLlmChunks() {
        FlattenedCallGraph callGraph = FlattenedCallGraph.builder()
                .rootSignature("calculate").methods(List.of()).build();
        when(analysisService.analyzeMethodStructured(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(AnalysisResult.success(callGraph, null));
        AgentAnalysisTools tools = new AgentAnalysisTools(
                analysisService, mockStreamingClient(List.of("業務", "說明", "內容")), objectMapper);

        String result = tools.findCallGraph(
                "test-repo", "pkg", "Cls", "method", emptyToolContext());

        assertThat(result).isEqualTo("業務說明內容");
    }

    @Test
    void findCallGraph_wiresCallGraphExpandTools_toInnerLlm() {
        FlattenedCallGraph callGraph = FlattenedCallGraph.builder()
                .rootSignature("calculate").methods(List.of()).build();
        when(analysisService.analyzeMethodStructured(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(AnalysisResult.success(callGraph, null));
        ChatClient innerClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(innerClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("ok"));
        AgentAnalysisTools tools = new AgentAnalysisTools(
                analysisService, innerClient, objectMapper);

        tools.findCallGraph("test-repo", "pkg", "Cls", "method", emptyToolContext());

        verify(requestSpec).tools(any(Object[].class));
    }

    @Test
    void findCallGraph_recordsCallToRecorder_whenPresent() {
        FlattenedCallGraph callGraph = FlattenedCallGraph.builder()
                .rootSignature("calculate").methods(List.of()).build();
        when(analysisService.analyzeMethodStructured(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(AnalysisResult.success(callGraph, null));
        ToolCallRecorder recorder = mock(ToolCallRecorder.class);
        AgentAnalysisTools tools = new AgentAnalysisTools(
                analysisService, mockStreamingClient(List.of("ok")), objectMapper);

        tools.findCallGraph("test-repo", "pkg", "Cls", "method",
                toolContextWith(Map.of("recorder", recorder)));

        verify(recorder).record(anyString(), anyString());
    }

    @Test
    void findCallGraph_returnsStructuredFailureJson_whenAnalysisFails() {
        when(analysisService.analyzeMethodStructured(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(AnalysisResult.failed(
                        AnalysisErrorCode.ENTRYPOINT_NOT_FOUND,
                        "Entrypoint method was not found",
                        "missing",
                        null));
        AgentAnalysisTools tools = new AgentAnalysisTools(
                analysisService, mock(ChatClient.class), objectMapper);

        String result = tools.findCallGraph(
                "test-repo", "pkg", "Cls", "missing", emptyToolContext());

        assertThat(result).contains("\"status\":\"FAILED\"");
        assertThat(result).contains("ENTRYPOINT_NOT_FOUND");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ChatClient mockStreamingClient(List<String> chunks) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.fromIterable(chunks));

        return client;
    }

    private ToolContext emptyToolContext() {
        return toolContextWith(Map.of());
    }

    private ToolContext toolContextWith(Map<String, Object> context) {
        return new ToolContext(context);
    }
}
