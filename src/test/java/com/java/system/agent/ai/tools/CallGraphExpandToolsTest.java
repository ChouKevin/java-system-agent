package com.java.system.agent.ai.tools;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.AnalysisErrorCode;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisStatus;
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
    void findCallGraph_callsStructuredAnalysisService_withCorrectArgs() {
        when(analysisService.analyzeMethodStructured(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(AnalysisResult.success(
                        FlattenedCallGraph.builder().methods(List.of()).build(),
                        null));

        tools.findCallGraph("test-repo",
                "com.example.service", "MainService", "calculate");

        verify(analysisService).analyzeMethodStructured(
                "test-repo", "com.example.service", "MainService", "calculate");
    }

    @Test
    void findCallGraph_returnsStructuredResult() {
        FlattenedCallGraph graph = FlattenedCallGraph.builder()
                .rootSignature("calculate").methods(List.of()).build();
        AnalysisResult<FlattenedCallGraph> expected = AnalysisResult.success(graph, null);
        when(analysisService.analyzeMethodStructured(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(expected);

        AnalysisResult<FlattenedCallGraph> result = tools.findCallGraph(
                "test-repo", "pkg", "MainService", "calculate");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void findCallGraph_returnsFailedResult_whenAnalysisServiceThrows() {
        when(analysisService.analyzeMethodStructured(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("parse error"));

        AnalysisResult<FlattenedCallGraph> result = tools.findCallGraph(
                "test-repo", "pkg", "MainService", "calculate");

        assertThat(result.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(AnalysisErrorCode.INTERNAL_ERROR);
    }
}
