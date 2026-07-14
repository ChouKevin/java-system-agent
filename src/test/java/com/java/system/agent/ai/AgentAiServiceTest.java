package com.java.system.agent.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.config.AgentLoopProperties;
import com.java.system.agent.ai.config.ChatMemoryLocks;
import com.java.system.agent.ai.evidence.CodeEvidenceTracker;
import com.java.system.agent.ai.evidence.EvidenceRequirement;
import com.java.system.agent.ai.loop.LlmRateLimiter;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.trace.LoopTraceStore;
import com.java.system.agent.ai.service.AgentAiService;
import com.java.system.agent.ai.tools.AgentAnalysisTools;
import com.java.system.agent.ai.tools.DocumentTools;
import com.java.system.agent.ai.tools.ToolNames;
import com.java.system.agent.analysis.port.RepoDocPort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAiServiceTest {

    @Test
    void analyzeWithTools_emitsVerifiedAnswerAndRunsVerifyStages() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("業務回覆"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: PASS");
        FakeChatMemory chatMemory = new FakeChatMemory();
        FakeLoopTraceStore traceStore = new FakeLoopTraceStore();

        AgentAiService service = new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                chatMemory,
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, new FakeToolCallingManager(),
                        null, new ObjectMapper(), defaultLoopProperties(), LlmRateLimiter.NOOP),
                new ObjectMapper(),
                traceStore,
                defaultLoopProperties(),
                LlmRateLimiter.NOOP,
                new ChatMemoryLocks());

        String joined = String.join("", service.analyzeWithTools("thread-1", "這個系統有哪些服務？")
                .collectList()
                .block());

        assertThat(joined).contains("業務回覆");
        assertThat(chatModel.verifierCalls()).isEqualTo(2);
        assertThat(chatMemory.addedMessages()).hasSize(2);
        assertThat(traceStore.recent("thread-1")).hasSize(1);
    }

    @Test
    void analyzeWithTools_skipsMemoryWriteBack_whenAnswerNotAccepted() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("草稿"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: REVISE — 證據不足");
        FakeChatMemory chatMemory = new FakeChatMemory();
        FakeLoopTraceStore traceStore = new FakeLoopTraceStore();

        AgentAiService service = new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                chatMemory,
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, new FakeToolCallingManager(),
                        null, new ObjectMapper(), defaultLoopProperties(), LlmRateLimiter.NOOP),
                new ObjectMapper(),
                traceStore,
                defaultLoopProperties(),
                LlmRateLimiter.NOOP,
                new ChatMemoryLocks());

        service.analyzeWithTools("thread-1", "這個系統有哪些服務？")
                .collectList()
                .block();

        assertThat(chatMemory.addedMessages()).isEmpty();
        assertThat(traceStore.recent("thread-1")).hasSize(1);
    }

    @Test
    void analyzeWithTools_serializesMemoryWriteBackForSameConversation() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("業務回覆"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: PASS");
        SlowChatMemory chatMemory = new SlowChatMemory();
        FakeLoopTraceStore traceStore = new FakeLoopTraceStore();
        AgentAiService service = new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                chatMemory,
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, new FakeToolCallingManager(),
                        null, new ObjectMapper(), defaultLoopProperties(), LlmRateLimiter.NOOP),
                new ObjectMapper(),
                traceStore,
                defaultLoopProperties(),
                LlmRateLimiter.NOOP,
                new ChatMemoryLocks());

        reactor.core.publisher.Flux.merge(IntStream.range(0, 4)
                        .mapToObj(index -> service.analyzeWithTools(
                                        "thread-1", "這個系統有哪些服務？")
                                .collectList())
                        .toList())
                .collectList()
                .block();

        assertThat(chatMemory.maxActiveAdds()).isEqualTo(1);
        assertThat(chatMemory.addedMessages()).hasSize(8);
    }

    @Test
    void analyzeWithTools_emitsTimeoutMessage_whenLoopStalls() {
        SlowChatModel chatModel = new SlowChatModel(500L);
        FakeChatMemory chatMemory = new FakeChatMemory();
        FakeLoopTraceStore traceStore = new FakeLoopTraceStore();
        AgentAiService service = new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                chatMemory,
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, new FakeToolCallingManager(),
                        null, new ObjectMapper(), defaultLoopProperties(), LlmRateLimiter.NOOP),
                new ObjectMapper(),
                traceStore,
                loopPropertiesWithOverall(100L),
                LlmRateLimiter.NOOP,
                new ChatMemoryLocks());

        String joined = String.join("", service.analyzeWithTools("thread-1", "這個系統有哪些服務？")
                .collectList()
                .block());

        assertThat(joined).isEqualTo("\n\n❌ 分析逾時，請稍後再試或縮小問題範圍");
        assertThat(joined).doesNotContain("interrupted", "SlowChatModel");
    }

    @Test
    void should_render_generic_processing_failure_when_outer_business_error_follows_valid_evidence() {
        assertGenericProcessingFailureOnOuterErrorAfterValidEvidence(
                "獎金在什麼條件下核發？",
                ToolNames.FIND_CALL_GRAPH);
    }

    @Test
    void should_render_generic_processing_failure_when_outer_api_error_follows_valid_evidence() {
        assertGenericProcessingFailureOnOuterErrorAfterValidEvidence(
                "GET /orders/{id} 的流程是什麼？",
                ToolNames.FIND_API_CALL_GRAPH);
    }

    @Test
    void should_render_fixed_generic_response_when_outer_docs_error_contains_internal_details() {
        String internalError = "com.secret.billing.Repository.findById(/var/lib/db) token=outer-secret";
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("服務摘要"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: PASS");
        AgentAiService service = new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                new FailingChatMemory(internalError),
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, new FakeToolCallingManager(),
                        null, new ObjectMapper(), defaultLoopProperties(), LlmRateLimiter.NOOP),
                new ObjectMapper(),
                new FakeLoopTraceStore(),
                defaultLoopProperties(),
                LlmRateLimiter.NOOP,
                new ChatMemoryLocks());

        List<String> chunks = service.analyzeWithTools("thread-1", "這個系統有哪些服務？")
                .collectList()
                .block();

        assertThat(chunks).isNotNull();
        assertThat(chunks.getLast()).isEqualTo("\n\n❌ 分析暫時無法完成，請稍後再試");
        assertThat(String.join("", chunks)).doesNotContain(
                "com.secret", "Repository", "/var/lib/db", "outer-secret");
    }

    @Test
    void should_replace_business_answer_when_model_never_collects_code_evidence() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("系統一定會直接核發獎金"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: PASS");
        AgentLoopProperties properties = loopPropertiesWithAnalystTurns(1);
        AgentAiService service = new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                new FakeChatMemory(),
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, new FakeToolCallingManager(),
                        null, new ObjectMapper(), properties, LlmRateLimiter.NOOP),
                new ObjectMapper(),
                new FakeLoopTraceStore(),
                properties,
                LlmRateLimiter.NOOP,
                new ChatMemoryLocks());

        String joined = String.join("", service.analyzeWithTools(
                "thread-1", "獎金在什麼條件下核發？").collectList().block());

        assertThat(joined).contains("無法從 codebase 驗證");
        assertThat(joined).doesNotContain("一定會直接核發獎金");
    }

    private void assertGenericProcessingFailureOnOuterErrorAfterValidEvidence(
            String userQuery, String toolName) {
        String internalError = "com.secret.billing.Repository.findById(/var/lib/db) token=outer-secret";
        ToolThenAnswerChatModel chatModel = new ToolThenAnswerChatModel(toolName, "安全的業務回答");
        EvidenceRecordingToolCallingManager toolCallingManager =
                new EvidenceRecordingToolCallingManager(toolName);
        AgentAiService service = new AgentAiService(
                chatModel,
                toolCallingManager,
                new FailingChatMemory(internalError),
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, toolCallingManager,
                        null, new ObjectMapper(), defaultLoopProperties(), LlmRateLimiter.NOOP),
                new ObjectMapper(),
                new FakeLoopTraceStore(),
                defaultLoopProperties(),
                LlmRateLimiter.NOOP,
                new ChatMemoryLocks());

        List<String> chunks = service.analyzeWithTools("thread-1", userQuery)
                .collectList()
                .block();

        assertThat(chunks).isNotNull();
        assertThat(chunks.getLast()).isEqualTo(
                "\n\n❌ 分析或回覆處理暫時失敗，請稍後再試。");
        assertThat(String.join("", chunks)).doesNotContain(
                "com.secret", "Repository", "/var/lib/db", "outer-secret",
                "未完成 API route 驗證", "無法從 codebase");
    }

    private static final class FakeChatModel implements ChatModel {

        private final ChatResponse promptResponse;
        private final String verifierResponse;
        private int verifierCalls;

        private FakeChatModel(ChatResponse promptResponse, String verifierResponse) {
            this.promptResponse = promptResponse;
            this.verifierResponse = verifierResponse;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return promptResponse;
        }

        @Override
        public String call(String message) {
            verifierCalls++;
            return verifierResponse;
        }

        private int verifierCalls() {
            return verifierCalls;
        }
    }

    private static final class SlowChatModel implements ChatModel {

        private final long sleepMillis;

        private SlowChatModel(long sleepMillis) {
            this.sleepMillis = sleepMillis;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("慢速回覆"))));
        }

        @Override
        public String call(String message) {
            return "VERDICT: PASS";
        }
    }

    private static final class ToolThenAnswerChatModel implements ChatModel {

        private final String toolName;
        private final String answer;
        private int promptCalls;

        private ToolThenAnswerChatModel(String toolName, String answer) {
            this.toolName = toolName;
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (promptCalls++ == 0) {
                AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                        "id-1", "function", toolName, "{}");
                AssistantMessage toolMessage = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build();
                return new ChatResponse(List.of(new Generation(toolMessage)));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override
        public String call(String message) {
            return "VERDICT: PASS";
        }
    }

    private static AgentLoopProperties defaultLoopProperties() {
        return loopPropertiesWithOverall(300_000L);
    }

    private static AgentLoopProperties loopPropertiesWithOverall(long overallMaxWallMs) {
        return new AgentLoopProperties(
                new AgentLoopProperties.Analyst(12, 120_000L, 2),
                new AgentLoopProperties.Translator(6, 60_000L),
                new AgentLoopProperties.Overall(overallMaxWallMs),
                new AgentLoopProperties.Trace(true, 20, 200),
                new AgentLoopProperties.RateLimit(true, 30, 1_000_000, 1_500, 300_000L));
    }

    private static AgentLoopProperties loopPropertiesWithAnalystTurns(int maxTurns) {
        return new AgentLoopProperties(
                new AgentLoopProperties.Analyst(maxTurns, 120_000L, 2),
                new AgentLoopProperties.Translator(6, 60_000L),
                new AgentLoopProperties.Overall(300_000L),
                new AgentLoopProperties.Trace(true, 20, 200),
                new AgentLoopProperties.RateLimit(true, 30, 1_000_000, 1_500, 300_000L));
    }

    private static final class FakeChatMemory implements ChatMemory {

        private final List<Message> addedMessages = new ArrayList<>();

        @Override
        public void add(String conversationId, List<Message> messages) {
            addedMessages.addAll(messages);
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.of();
        }

        @Override
        public void clear(String conversationId) {
            addedMessages.clear();
        }

        private List<Message> addedMessages() {
            return List.copyOf(addedMessages);
        }
    }

    private static final class SlowChatMemory implements ChatMemory {

        private final List<Message> addedMessages = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger activeAdds = new AtomicInteger();
        private final AtomicInteger maxActiveAdds = new AtomicInteger();

        @Override
        public void add(String conversationId, List<Message> messages) {
            int active = activeAdds.incrementAndGet();
            maxActiveAdds.accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(50);
                addedMessages.addAll(messages);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } finally {
                activeAdds.decrementAndGet();
            }
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.of();
        }

        @Override
        public void clear(String conversationId) {
            addedMessages.clear();
        }

        private int maxActiveAdds() {
            return maxActiveAdds.get();
        }

        private List<Message> addedMessages() {
            return List.copyOf(addedMessages);
        }
    }

    private static final class FailingChatMemory implements ChatMemory {

        private final String errorMessage;

        private FailingChatMemory(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            throw new IllegalStateException(errorMessage);
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.of();
        }

        @Override
        public void clear(String conversationId) {
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
                    .conversationHistory(List.of())
                    .build();
        }
    }

    private static final class EvidenceRecordingToolCallingManager implements ToolCallingManager {

        private final String toolName;

        private EvidenceRecordingToolCallingManager(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            ToolCallingChatOptions chatOptions = (ToolCallingChatOptions) prompt.getOptions();
            Map<String, Object> toolContext = chatOptions.getToolContext();
            CodeEvidenceTracker tracker = (CodeEvidenceTracker) toolContext.get(
                    CodeEvidenceTracker.CONTEXT_KEY);
            tracker.recordVerified(toolName, "order-service", "/orders/{id}", List.of());
            return ToolExecutionResult.builder()
                    .conversationHistory(List.of(new UserMessage("tool observation")))
                    .build();
        }
    }

    private static final class FakeRepoDocPort implements RepoDocPort {

        @Override
        public String readServiceMap() {
            return "";
        }

        @Override
        public String readBusinessMap(String repoId) {
            return "";
        }

        @Override
        public String readBusinessGroupDoc(String repoId, String groupName) {
            return "";
        }

        @Override
        public String readSummary(String repoId) {
            return "";
        }
    }

    private static final class FakeLoopTraceStore implements LoopTraceStore {

        private final List<LoopTrace> traces = new ArrayList<>();

        @Override
        public void save(String conversationId, LoopTrace trace) {
            traces.add(trace);
        }

        @Override
        public List<LoopTrace> recent(String conversationId) {
            return List.copyOf(traces);
        }

        @Override
        public java.util.Optional<LoopTrace> byTraceId(String traceId) {
            return traces.stream()
                    .filter(trace -> trace.traceId().equals(traceId))
                    .findFirst();
        }
    }
}
