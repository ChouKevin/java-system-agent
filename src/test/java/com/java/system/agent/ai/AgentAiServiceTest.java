package com.java.system.agent.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.config.AgentLoopProperties;
import com.java.system.agent.ai.config.ChatMemoryLocks;
import com.java.system.agent.ai.loop.LlmRateLimiter;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.trace.LoopTraceStore;
import com.java.system.agent.ai.service.AgentAiService;
import com.java.system.agent.ai.tools.AgentAnalysisTools;
import com.java.system.agent.ai.tools.DocumentTools;
import com.java.system.agent.analysis.port.RepoDocPort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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

        String joined = String.join("", service.analyzeWithTools("thread-1", "如何計算獎金?")
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

        service.analyzeWithTools("thread-1", "如何計算獎金?")
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
                        .mapToObj(index -> service.analyzeWithTools("thread-1", "問題" + index)
                                .collectList())
                        .toList())
                .collectList()
                .block();

        assertThat(chatMemory.maxActiveAdds()).isEqualTo(1);
        assertThat(chatMemory.addedMessages()).hasSize(8);
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

    private static AgentLoopProperties defaultLoopProperties() {
        return new AgentLoopProperties(
                new AgentLoopProperties.Analyst(12, 120_000L, 2),
                new AgentLoopProperties.Translator(6, 60_000L),
                new AgentLoopProperties.Trace(true, 20, 200),
                new AgentLoopProperties.RateLimit(true, 30, 1_000_000, 1_500));
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
