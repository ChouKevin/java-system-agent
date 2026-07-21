package com.java.system.agent.ai.tools;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.AnalysisErrorCode;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisStatus;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.MethodId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

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
        when(analysisService.analyzeMethodExplainableStructured(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(AnalysisResult.success(
                        explainableGraph("calculate"),
                        null));

        tools.findCallGraph("demo-repo",
                "com.example.service", "MainService", "calculate");

        verify(analysisService).analyzeMethodExplainableStructured(
                "demo-repo", "com.example.service", "MainService", "calculate");
    }

    @Test
    void findCallGraph_returnsStructuredResult() {
        ExplainableCallGraph graph = explainableGraph("calculate");
        AnalysisResult<ExplainableCallGraph> expected = AnalysisResult.success(graph, null);
        when(analysisService.analyzeMethodExplainableStructured(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(expected);

        AnalysisResult<ExplainableCallGraph> result = tools.findCallGraph(
                "demo-repo", "pkg", "MainService", "calculate");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void findCallGraph_returnsFailedResultAndLogsWithoutThrowableStackTrace_whenAnalysisServiceThrows() {
        Logger logger = (Logger) LoggerFactory.getLogger(CallGraphExpandTools.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean originalAdditive = logger.isAdditive();
        when(analysisService.analyzeMethodExplainableStructured(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("parse error"));

        AnalysisResult<ExplainableCallGraph> result;
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        try {
            result = tools.findCallGraph("demo-repo", "pkg", "MainService", "calculate");
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(originalAdditive);
            appender.stop();
        }

        assertThat(result.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(AnalysisErrorCode.INTERNAL_ERROR);
        assertThat(appender.list)
                .anySatisfy(event -> {
                    assertThat(event.getFormattedMessage())
                            .contains("CallGraphExpandTools failed for MainService.calculate")
                            .contains("parse error");
                    assertThat(event.getThrowableProxy()).isNull();
                });
    }

    private ExplainableCallGraph explainableGraph(String methodName) {
        MethodId root = new MethodId("demo-repo", "pkg", "MainService", methodName, List.of());
        return new ExplainableCallGraph(
                root,
                List.of(),
                List.of(),
                Map.of(),
                FlattenedCallGraph.builder()
                        .rootSignature(methodName)
                        .methods(List.of())
                        .build());
    }
}
