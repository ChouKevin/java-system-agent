package com.java.system.agent.ai.tools;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CallGraphExpandToolsTest {

    @Mock
    private AnalysisService analysisService;

    private CallGraphExpandTools tools;

    @BeforeEach
    void setUp() {
        tools = new CallGraphExpandTools(analysisService);
    }

    @Test
    void findCallGraph_callsAnalysisService_withCorrectArgs() {
        when(analysisService.analyzeMethod(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(FlattenedCallGraph.builder().methods(List.of()).build());

        tools.findCallGraph("test-repo",
                "com.example.service", "MainService", "calculate");

        verify(analysisService).analyzeMethod(
                "test-repo", "com.example.service", "MainService", "calculate");
    }

    @Test
    void findCallGraph_returnsFlattenedCallGraph() {
        FlattenedCallGraph expected = FlattenedCallGraph.builder()
                .rootSignature("calculate").methods(List.of()).build();
        when(analysisService.analyzeMethod(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(expected);

        FlattenedCallGraph result = tools.findCallGraph(
                "test-repo", "pkg", "MainService", "calculate");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void findCallGraph_returnsEmptyGraph_whenAnalysisServiceReturnsNull() {
        when(analysisService.analyzeMethod(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        FlattenedCallGraph result = tools.findCallGraph(
                "test-repo", "pkg", "MainService", "calculate");

        assertThat(result.getMethods()).isEmpty();
    }

    @Test
    void findCallGraph_returnsEmptyGraph_whenAnalysisServiceThrows() {
        when(analysisService.analyzeMethod(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("parse error"));

        FlattenedCallGraph result = tools.findCallGraph(
                "test-repo", "pkg", "MainService", "calculate");

        assertThat(result.getMethods()).isEmpty();
    }
}
