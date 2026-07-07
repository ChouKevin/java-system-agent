package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.config.AgentLoopProperties;
import com.java.system.agent.ai.loop.LlmRateLimiter;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.LoopTraceCollector;
import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.AnalysisErrorCode;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.MethodId;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnalysisToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void findCallGraph_callsAnalysisService_withCorrectArgs() {
        ExplainableCallGraph callGraph = explainableGraph("calculate");
        FakeAnalysisService analysisService = new FakeAnalysisService(AnalysisResult.success(callGraph, null));
        AgentAnalysisTools tools = newTools(analysisService);

        tools.findCallGraph("test-repo",
                "com.example.service", "MainService", "calculate", emptyToolContext());

        assertThat(analysisService.lastRepoId()).isEqualTo("test-repo");
        assertThat(analysisService.lastPackageName()).isEqualTo("com.example.service");
        assertThat(analysisService.lastClassName()).isEqualTo("MainService");
        assertThat(analysisService.lastMethodSignature()).isEqualTo("calculate");
    }

    @Test
    void findCallGraph_returnsTranslatorLoopAnswer() {
        ExplainableCallGraph callGraph = explainableGraph("calculate");
        FakeAnalysisService analysisService = new FakeAnalysisService(AnalysisResult.success(callGraph, null));
        AgentAnalysisTools tools = newTools(analysisService);

        String result = tools.findCallGraph(
                "test-repo", "pkg", "Cls", "method", emptyToolContext());

        assertThat(result).isEqualTo("翻譯完成");
    }

    @Test
    void findCallGraph_publishesTranslatorTraceToCollector() {
        ExplainableCallGraph callGraph = explainableGraph("calculate");
        FakeAnalysisService analysisService = new FakeAnalysisService(AnalysisResult.success(callGraph, null));
        LoopTraceCollector collector = new LoopTraceCollector();
        AgentAnalysisTools tools = newTools(analysisService);

        tools.findCallGraph(
                "test-repo",
                "pkg",
                "Cls",
                "method",
                new ToolContext(Map.of("userQuery", "如何計算獎金?", "traceCollector", collector)));

        assertThat(collector.drain())
                .extracting(LoopTrace::role)
                .containsExactly("translator");
        assertThat(collector.drain()).isEmpty();
    }

    @Test
    void findCallGraph_returnsStructuredFailureJson_whenAnalysisFails() {
        FakeAnalysisService analysisService = new FakeAnalysisService(AnalysisResult.failed(
                AnalysisErrorCode.ENTRYPOINT_NOT_FOUND,
                "Entrypoint method was not found",
                "missing",
                null));
        AgentAnalysisTools tools = newTools(analysisService);

        String result = tools.findCallGraph(
                "test-repo", "pkg", "Cls", "missing", emptyToolContext());

        assertThat(result).contains("\"status\":\"FAILED\"");
        assertThat(result).contains("ENTRYPOINT_NOT_FOUND");
    }

    @Test
    void class_isSingletonComponent() {
        assertThat(AgentAnalysisTools.class.getAnnotation(Component.class)).isNotNull();
    }

    private AgentAnalysisTools newTools(FakeAnalysisService analysisService) {
        return new AgentAnalysisTools(
                new FakeChatModel("翻譯完成"),
                new FakeToolCallingManager(),
                analysisService,
                objectMapper,
                defaultLoopProperties(),
                LlmRateLimiter.NOOP);
    }

    private AgentLoopProperties defaultLoopProperties() {
        return new AgentLoopProperties(
                new AgentLoopProperties.Analyst(12, 120_000L, 2),
                new AgentLoopProperties.Translator(2, 60_000L),
                new AgentLoopProperties.Trace(true, 20, 200),
                new AgentLoopProperties.RateLimit(true, 30, 1_000_000, 1_500));
    }

    private ToolContext emptyToolContext() {
        return new ToolContext(Map.of("userQuery", "如何計算獎金?"));
    }

    private ExplainableCallGraph explainableGraph(String methodName) {
        MethodId root = new MethodId("test-repo", "pkg", "Cls", methodName, List.of());
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

    private static final class FakeAnalysisService extends AnalysisService {

        private final AnalysisResult<ExplainableCallGraph> result;
        private String lastRepoId;
        private String lastPackageName;
        private String lastClassName;
        private String lastMethodSignature;

        private FakeAnalysisService(AnalysisResult<ExplainableCallGraph> result) {
            super(null, null, null, null, null, null, null, null);
            this.result = result;
        }

        @Override
        public AnalysisResult<ExplainableCallGraph> analyzeMethodExplainableStructured(
                String repoId, String packageName, String className, String methodName) {
            this.lastRepoId = repoId;
            this.lastPackageName = packageName;
            this.lastClassName = className;
            this.lastMethodSignature = methodName;
            return result;
        }

        private String lastRepoId() {
            return lastRepoId;
        }

        private String lastPackageName() {
            return lastPackageName;
        }

        private String lastClassName() {
            return lastClassName;
        }

        private String lastMethodSignature() {
            return lastMethodSignature;
        }
    }

    private static final class FakeChatModel implements ChatModel {

        private final String responseText;

        private FakeChatModel(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
        }
    }

    private static final class FakeToolCallingManager implements ToolCallingManager {

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            return ToolExecutionResult.builder()
                    .conversationHistory(List.<Message>of())
                    .build();
        }
    }
}
