package com.java.system.agent.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.config.AgentLoopProperties;
import com.java.system.agent.ai.config.ChatMemoryLocks;
import com.java.system.agent.ai.evidence.CodeEvidenceTracker;
import com.java.system.agent.ai.evidence.EvidenceRequirement;
import com.java.system.agent.ai.loop.LlmRateLimiter;
import com.java.system.agent.ai.loop.TerminationReason;
import com.java.system.agent.ai.service.AgentAiService;
import com.java.system.agent.ai.tools.AgentAnalysisTools;
import com.java.system.agent.ai.tools.DocumentTools;
import com.java.system.agent.ai.tools.ToolNames;
import com.java.system.agent.ai.trace.AgentRequestContext;
import com.java.system.agent.ai.trace.AgentTraceRecord;
import com.java.system.agent.ai.trace.AgentTraceStore;
import com.java.system.agent.ai.trace.AgentTraceSummary;
import com.java.system.agent.ai.trace.MemoryMessageSnapshot;
import com.java.system.agent.ai.trace.TraceRecordMapper;
import com.java.system.agent.ai.trace.TraceSearchQuery;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAiServiceTest {

    @Test
    void analyzeWithTools_emitsVerifiedAnswerAndRunsVerifyStages() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("業務回覆"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: PASS");
        FakeChatMemory chatMemory = new FakeChatMemory();
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        AgentAiService service = service(
                chatModel, chatMemory, traceStore, defaultLoopProperties());

        String joined = String.join("", service.analyzeWithTools("thread-1", "這個系統有哪些服務？")
                .collectList()
                .block());

        assertThat(joined).contains("業務回覆");
        assertThat(chatModel.verifierCalls()).isEqualTo(2);
        assertThat(chatMemory.addedMessages()).hasSize(2);
        assertThat(traceStore.saved()).hasSize(1);
    }

    @Test
    void analyzeWithTools_skipsMemoryWriteBack_whenAnswerNotAccepted() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("草稿"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: REVISE — 證據不足");
        FakeChatMemory chatMemory = new FakeChatMemory();
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        AgentAiService service = service(
                chatModel, chatMemory, traceStore, defaultLoopProperties());

        service.analyzeWithTools("thread-1", "這個系統有哪些服務？")
                .collectList()
                .block();

        assertThat(chatMemory.addedMessages()).isEmpty();
        assertThat(traceStore.saved()).hasSize(1);
    }

    @Test
    void subscriberCancellation_persistsCancelledTraceWithoutSlackResponse() throws InterruptedException {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("不應送出的答案"))));
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        AgentAiService service = service(
                new FakeChatModel(response, "VERDICT: PASS"),
                new FakeChatMemory(),
                traceStore,
                defaultLoopProperties());
        AgentRequestContext context = requestContext("trace-cancelled");

        service.analyzeWithTools(context).take(1).blockLast();

        assertThat(traceStore.awaitSave()).isTrue();
        assertThat(traceStore.saved()).singleElement().satisfies(trace -> {
            assertThat(trace.traceId()).isEqualTo(context.traceId());
            assertThat(trace.terminationReason()).isEqualTo(TerminationReason.CANCELLED);
            assertThat(trace.slackResponse()).isEmpty();
            assertThat(trace.payload().loopTrace().steps()).isNotEmpty();
        });
    }

    @Test
    void analyzeWithTools_emitsFixedFallbackWithoutSelfEvalRejectedCandidate() {
        String rejectedCandidate = "不得外洩的模型草稿";
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage(rejectedCandidate))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: REVISE — 證據不足");
        AgentLoopProperties properties = loopPropertiesWithAnalystTurns(1);
        AgentAiService service = service(
                chatModel, new FakeChatMemory(), new FakeAgentTraceStore(), properties);

        List<String> chunks = service.analyzeWithTools("thread-1", "這個系統有哪些服務？")
                .collectList()
                .block();

        assertThat(String.join("", chunks)).contains("回答未通過驗證，請稍後再試。");
        assertThat(chunks).noneMatch(chunk -> chunk.contains(rejectedCandidate));
    }

    @Test
    void unacceptedRun_persistsRejectedDraftThenEmitsSafeTraceMessage() {
        String rejectedCandidate = "被拒草稿";
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage(rejectedCandidate))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: REVISE — 證據不足");
        FakeChatMemory chatMemory = new FakeChatMemory(List.of(
                new UserMessage("上一題"), new AssistantMessage("上一答")));
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        AgentLoopProperties properties = loopPropertiesWithAnalystTurns(1);
        AgentAiService service = service(chatModel, chatMemory, traceStore, properties);
        AgentRequestContext context = requestContext("trace-rejected");

        String output = String.join("", service.analyzeWithTools(context).collectList().block());

        assertThat(output).contains(
                "回答未通過驗證，請稍後再試。追蹤編號：" + context.traceId());
        assertThat(output).doesNotContain(rejectedCandidate);
        assertThat(traceStore.saved()).singleElement()
                .satisfies(trace -> {
                    assertThat(trace.finalCandidate()).isEqualTo(rejectedCandidate);
                    assertThat(trace.slackResponse()).contains(context.traceId());
                    assertThat(trace.payload().memorySnapshot()).containsExactly(
                            new MemoryMessageSnapshot("USER", "上一題"),
                            new MemoryMessageSnapshot("ASSISTANT", "上一答"));
                });
        assertThat(chatMemory.getCalls()).isEqualTo(1);
        assertThat(chatMemory.addedMessages()).isEmpty();
    }

    @Test
    void unacceptedRun_storeFailureEmitsSafeMessageWithoutTraceNumber() {
        String rejectedCandidate = "被拒草稿";
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage(rejectedCandidate))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: REVISE — 證據不足");
        FakeChatMemory chatMemory = new FakeChatMemory();
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        traceStore.failOnSave();
        AgentAiService service = service(
                chatModel, chatMemory, traceStore, loopPropertiesWithAnalystTurns(1));
        AgentRequestContext context = requestContext("trace-store-failure");

        String output = String.join("", service.analyzeWithTools(context).collectList().block());

        assertThat(output).contains("回答未通過驗證，請稍後再試。");
        assertThat(output).doesNotContain(context.traceId());
        assertThat(output).doesNotContain(rejectedCandidate);
        assertThat(chatMemory.addedMessages()).isEmpty();
    }

    @Test
    void acceptedRun_storeFailureStillEmitsVerifiedAnswerAndWritesMemory() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("已驗證回答"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: PASS");
        FakeChatMemory chatMemory = new FakeChatMemory();
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        traceStore.failOnSave();
        AgentAiService service = service(chatModel, chatMemory, traceStore, defaultLoopProperties());

        String output = String.join("", service.analyzeWithTools(
                requestContext("trace-accepted-store-failure")).collectList().block());

        assertThat(output).contains("已驗證回答");
        assertThat(chatMemory.addedMessages())
                .extracting(Message::getText)
                .containsExactly("這個系統有哪些服務？", "已驗證回答");
    }

    @Test
    void acceptedRun_slowTraceStoreAfterDoneDoesNotBecomeOverallTimeout() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("已驗證回答"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: PASS");
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        traceStore.delaySaveBy(700L);
        AgentAiService service = service(
                chatModel, new FakeChatMemory(), traceStore, loopPropertiesWithOverall(500L));

        String output = String.join("", service.analyzeWithTools(
                requestContext("trace-slow-terminal-store")).collectList().block());

        assertThat(output).contains("已驗證回答");
        assertThat(output).doesNotContain("分析逾時");
        assertThat(traceStore.saved()).singleElement()
                .satisfies(trace -> assertThat(trace.terminationReason())
                        .isEqualTo(TerminationReason.ACCEPTED));
    }

    @Test
    void acceptedRun_memoryFailureIsBestEffortAndSavesOneNormalTrace() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("已驗證回答"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: PASS");
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        FailingChatMemory chatMemory = new FailingChatMemory("memory unavailable");
        AgentAiService service = service(
                chatModel, chatMemory, traceStore, defaultLoopProperties());

        String output = String.join("", service.analyzeWithTools(
                requestContext("trace-accepted-memory-failure")).collectList().block());

        assertThat(output).contains("已驗證回答");
        assertThat(output).doesNotContain(
                "分析暫時無法完成", "分析或回覆處理暫時失敗");
        assertThat(chatMemory.addCalls()).isEqualTo(1);
        assertThat(traceStore.saved()).singleElement()
                .satisfies(trace -> assertThat(trace.terminationReason())
                        .isEqualTo(TerminationReason.ACCEPTED));
    }

    @Test
    void analyzeWithTools_serializesMemoryWriteBackForSameConversation() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("業務回覆"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: PASS");
        SlowChatMemory chatMemory = new SlowChatMemory();
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        AgentAiService service = service(
                chatModel, chatMemory, traceStore, defaultLoopProperties());

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
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        AgentAiService service = service(
                chatModel, chatMemory, traceStore, loopPropertiesWithOverall(100L));
        AgentRequestContext context = requestContext("trace-timeout");

        String joined = String.join("", service.analyzeWithTools(context)
                .collectList()
                .block());

        assertThat(joined).isEqualTo("\n\n❌ 分析逾時，請稍後再試或縮小問題範圍");
        assertThat(joined).doesNotContain("interrupted", "SlowChatModel");
        assertThat(traceStore.saved()).singleElement().satisfies(trace -> {
            assertThat(trace.traceId()).isEqualTo(context.traceId());
            assertThat(trace.terminationReason()).isEqualTo(TerminationReason.OVERALL_TIMEOUT);
            assertThat(trace.payload().loopTrace().terminationReason())
                    .isEqualTo(TerminationReason.OVERALL_TIMEOUT);
            assertThat(trace.slackResponse()).isEqualTo(joined);
        });
    }

    @Test
    void timeout_storeFailureStillReturnsExistingTimeoutResponse() {
        SlowChatModel chatModel = new SlowChatModel(500L);
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        traceStore.failOnSave();
        AgentAiService service = service(
                chatModel, new FakeChatMemory(), traceStore, loopPropertiesWithOverall(100L));

        String output = String.join("", service.analyzeWithTools(
                requestContext("trace-timeout-store-failure")).collectList().block());

        assertThat(output).isEqualTo("\n\n❌ 分析逾時，請稍後再試或縮小問題範圍");
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
        FailingChatModel chatModel = new FailingChatModel(internalError);
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        AgentAiService service = new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                new FakeChatMemory(),
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, new FakeToolCallingManager(),
                        null, new ObjectMapper(), defaultLoopProperties(), LlmRateLimiter.NOOP),
                new ObjectMapper(),
                new TraceRecordMapper(),
                traceStore,
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
        assertThat(traceStore.saved()).singleElement()
                .satisfies(trace -> assertThat(trace.terminationReason())
                        .isEqualTo(TerminationReason.STEP_ERROR));
    }

    @Test
    void should_emitFixedFallback_whenModelNeverCollectsCodeEvidence() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("系統一定會直接核發獎金"))));
        FakeChatModel chatModel = new FakeChatModel(response, "VERDICT: PASS");
        AgentLoopProperties properties = loopPropertiesWithAnalystTurns(1);
        AgentAiService service = service(
                chatModel, new FakeChatMemory(), new FakeAgentTraceStore(), properties);

        String joined = String.join("", service.analyzeWithTools(
                "thread-1", "獎金在什麼條件下核發？").collectList().block());

        assertThat(joined).contains("回答未通過驗證，請稍後再試。");
        assertThat(joined).doesNotContain("一定會直接核發獎金");
    }

    @Test
    void outerError_storeFailurePreservesEvidenceGovernedResponse() {
        String internalError = "com.secret.Repository token=outer-secret";
        ToolThenFailingChatModel chatModel = new ToolThenFailingChatModel(
                ToolNames.FIND_CALL_GRAPH, internalError);
        EvidenceRecordingToolCallingManager toolCallingManager =
                new EvidenceRecordingToolCallingManager(ToolNames.FIND_CALL_GRAPH);
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        traceStore.failOnSave();
        AgentAiService service = new AgentAiService(
                chatModel,
                toolCallingManager,
                new FakeChatMemory(),
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, toolCallingManager,
                        null, new ObjectMapper(), defaultLoopProperties(), LlmRateLimiter.NOOP),
                new ObjectMapper(),
                new TraceRecordMapper(),
                traceStore,
                defaultLoopProperties(),
                LlmRateLimiter.NOOP,
                new ChatMemoryLocks());

        AgentRequestContext context = new AgentRequestContext(
                "trace-outer-store-failure", "U1", "T1", "C1", "E1", "thread-1",
                "獎金在什麼條件下核發？", Instant.parse("2026-07-15T01:23:04Z"));
        String output = String.join("", service.analyzeWithTools(context).collectList().block());

        assertThat(output).endsWith("\n\n❌ 分析或回覆處理暫時失敗，請稍後再試。");
        assertThat(output).doesNotContain("com.secret", "Repository", "outer-secret");
        assertThat(traceStore.saved()).isEmpty();
    }

    private static AgentAiService service(
            ChatModel chatModel,
            ChatMemory chatMemory,
            AgentTraceStore traceStore,
            AgentLoopProperties properties) {
        return new AgentAiService(
                chatModel,
                new FakeToolCallingManager(),
                chatMemory,
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, new FakeToolCallingManager(),
                        null, new ObjectMapper(), properties, LlmRateLimiter.NOOP),
                new ObjectMapper(),
                new TraceRecordMapper(),
                traceStore,
                properties,
                LlmRateLimiter.NOOP,
                new ChatMemoryLocks());
    }

    private static AgentRequestContext requestContext(String traceId) {
        return new AgentRequestContext(
                traceId, "U1", "T1", "C1", "E1", "thread-1",
                "這個系統有哪些服務？", Instant.parse("2026-07-15T01:23:04Z"));
    }

    private void assertGenericProcessingFailureOnOuterErrorAfterValidEvidence(
            String userQuery, String toolName) {
        String internalError = "com.secret.billing.Repository.findById(/var/lib/db) token=outer-secret";
        ToolThenFailingChatModel chatModel = new ToolThenFailingChatModel(toolName, internalError);
        EvidenceRecordingToolCallingManager toolCallingManager =
                new EvidenceRecordingToolCallingManager(toolName);
        FakeAgentTraceStore traceStore = new FakeAgentTraceStore();
        AgentAiService service = new AgentAiService(
                chatModel,
                toolCallingManager,
                new FakeChatMemory(),
                new DocumentTools(new FakeRepoDocPort()),
                new AgentAnalysisTools(chatModel, toolCallingManager,
                        null, new ObjectMapper(), defaultLoopProperties(), LlmRateLimiter.NOOP),
                new ObjectMapper(),
                new TraceRecordMapper(),
                traceStore,
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
        assertThat(traceStore.saved()).singleElement()
                .satisfies(trace -> assertThat(trace.terminationReason())
                        .isEqualTo(TerminationReason.STEP_ERROR));
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

    private static final class FailingChatModel implements ChatModel {

        private final String errorMessage;

        private FailingChatModel(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new AssertionError(errorMessage);
        }

        @Override
        public String call(String message) {
            throw new AssertionError(errorMessage);
        }
    }

    private static final class ToolThenFailingChatModel implements ChatModel {

        private final String toolName;
        private final String errorMessage;
        private int promptCalls;

        private ToolThenFailingChatModel(String toolName, String errorMessage) {
            this.toolName = toolName;
            this.errorMessage = errorMessage;
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
            throw new AssertionError(errorMessage);
        }

        @Override
        public String call(String message) {
            throw new AssertionError(errorMessage);
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
        private final List<Message> initialMessages;
        private int getCalls;

        private FakeChatMemory() {
            this(List.of());
        }

        private FakeChatMemory(List<Message> initialMessages) {
            this.initialMessages = List.copyOf(initialMessages);
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            addedMessages.addAll(messages);
        }

        @Override
        public List<Message> get(String conversationId) {
            getCalls++;
            return initialMessages;
        }

        @Override
        public void clear(String conversationId) {
            addedMessages.clear();
        }

        private List<Message> addedMessages() {
            return List.copyOf(addedMessages);
        }

        private int getCalls() {
            return getCalls;
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
        private int addCalls;

        private FailingChatMemory(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            addCalls++;
            throw new IllegalStateException(errorMessage);
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.of();
        }

        @Override
        public void clear(String conversationId) {
        }

        private int addCalls() {
            return addCalls;
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

    private static final class FakeAgentTraceStore implements AgentTraceStore {

        private final List<AgentTraceRecord> traces = new ArrayList<>();
        private final CountDownLatch saveLatch = new CountDownLatch(1);
        private boolean failOnSave;
        private long saveDelayMillis;

        @Override
        public void save(AgentTraceRecord trace) {
            if (failOnSave) {
                throw new IllegalStateException("trace store unavailable");
            }
            traces.add(trace);
            saveLatch.countDown();
            try {
                Thread.sleep(saveDelayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            }
        }

        @Override
        public List<AgentTraceSummary> search(TraceSearchQuery query) {
            return List.of();
        }

        @Override
        public List<AgentTraceRecord> conversation(String conversationId) {
            return List.of();
        }

        @Override
        public List<AgentTraceRecord> recent(String conversationId) {
            return List.of();
        }

        @Override
        public Optional<AgentTraceRecord> byTraceId(String traceId) {
            return Optional.empty();
        }

        private void failOnSave() {
            failOnSave = true;
        }

        private void delaySaveBy(long delayMillis) {
            saveDelayMillis = delayMillis;
        }

        private List<AgentTraceRecord> saved() {
            return List.copyOf(traces);
        }

        private boolean awaitSave() throws InterruptedException {
            return saveLatch.await(2, TimeUnit.SECONDS);
        }
    }
}
