package com.java.system.agent.ai.loop;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatModelStepTest {

    private final ChatOptions options = ToolCallingChatOptions.builder()
            .internalToolExecutionEnabled(false)
            .build();
    private final List<Message> seed = List.of(
            new SystemMessage("s"),
            new UserMessage("如何計算獎金?"));

    @Test
    void finalTurn_returnsAnswerAsCandidate() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("業務回覆"))));
        ChatModel chatModel = new FakeChatModel(response);

        ChatModelStep step = new ChatModelStep(chatModel, new FakeToolCallingManager(List.of()), options, seed);
        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "如何計算獎金?")));

        assertThat(outcome.isFinalCandidate()).isTrue();
        assertThat(outcome.candidate().answer()).isEqualTo("業務回覆");
    }

    @Test
    void step_recordsDurationAndTokenUsage() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(100, 20))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("答"))), metadata);
        ChatModel chatModel = new FakeChatModel(response);

        ChatModelStep step = new ChatModelStep(chatModel, new FakeToolCallingManager(List.of()), options, seed);
        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "q")));

        assertThat(outcome.metrics().promptTokens()).isEqualTo(100);
        assertThat(outcome.metrics().completionTokens()).isEqualTo(20);
        assertThat(outcome.metrics().durationMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void step_acquiresAndRecordsLlmRateLimit() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(10, 5))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("答"))), metadata);
        FakeLlmRateLimiter rateLimiter = new FakeLlmRateLimiter();

        ChatModelStep step = new ChatModelStep(
                new FakeChatModel(response),
                new FakeToolCallingManager(List.of()),
                options,
                seed,
                new LoopTraceCollector(),
                rateLimiter);

        step.step(LoopState.init(new LoopRequest("t", "q")));

        assertThat(rateLimiter.acquired).isEqualTo(1);
        assertThat(rateLimiter.recorded).isEqualTo(1);
    }

    @Test
    void step_withoutMetadataRecordsNoTokenUsage() {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("答"))), null);
        ChatModel chatModel = new FakeChatModel(response);

        ChatModelStep step = new ChatModelStep(chatModel, new FakeToolCallingManager(List.of()), options, seed);
        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "q")));

        assertThat(outcome.candidate().answer()).isEqualTo("答");
        assertThat(outcome.metrics().promptTokens()).isZero();
        assertThat(outcome.metrics().completionTokens()).isZero();
        assertThat(outcome.metrics().durationMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void actTurn_executesToolsAndReportsNames() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "id1", "function", "read_service_map", "{}");
        AssistantMessage toolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(toolMessage)));
        ChatModel chatModel = new FakeChatModel(response);
        ToolCallingManager toolCallingManager = new FakeToolCallingManager(List.of(new UserMessage("obs")));

        ChatModelStep step = new ChatModelStep(chatModel, toolCallingManager, options, seed);
        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "q")));

        assertThat(outcome.isFinalCandidate()).isFalse();
        assertThat(outcome.toolNames()).containsExactly("read_service_map");
    }

    @Test
    void actTurn_drainsChildTracesFromCollector() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "id1", "function", "find_call_graph", "{}");
        AssistantMessage toolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        LoopTrace child = new LoopTrace("child-1", "translator", "業務翻譯", true, List.of(), List.of());
        LoopTraceCollector collector = new LoopTraceCollector();
        collector.add(child);

        ChatModelStep step = new ChatModelStep(
                new FakeChatModel(new ChatResponse(List.of(new Generation(toolMessage)))),
                new FakeToolCallingManager(List.of(new UserMessage("obs"))),
                options,
                seed,
                collector);
        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "q")));

        assertThat(outcome.childTraces()).containsExactly(child);
        assertThat(collector.drain()).isEmpty();
    }

    @Test
    void forceAnswer_callsModelWithToolsOmitted() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("盡力答案"))));
        ChatModel chatModel = new FakeChatModel(response);
        FakeLlmRateLimiter rateLimiter = new FakeLlmRateLimiter();

        ChatModelStep step = new ChatModelStep(
                chatModel,
                new FakeToolCallingManager(List.of()),
                options,
                seed,
                new LoopTraceCollector(),
                rateLimiter);

        Candidate answer = step.forceAnswer(LoopState.init(new LoopRequest("t", "q"))
                .injectCritique("證據不足"));

        assertThat(answer.answer()).isEqualTo("盡力答案");
        assertThat(rateLimiter.acquired).isEqualTo(1);
        assertThat(rateLimiter.recorded).isEqualTo(1);
    }

    @Test
    void step_sendsOnlyOneSystemMessage_whenCritiqueIsInjected() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("修正版"))));
        FakeChatModel chatModel = new FakeChatModel(response);
        ChatModelStep step = new ChatModelStep(chatModel, new FakeToolCallingManager(List.of()), options, seed);

        step.step(LoopState.init(new LoopRequest("t", "q"))
                .injectCritique("證據不足"));

        assertThat(chatModel.lastPrompt.getInstructions())
                .filteredOn(SystemMessage.class::isInstance)
                .hasSize(1);
    }

    @Test
    void forceAnswer_sendsOnlyOneSystemMessage_whenCritiqueIsInjected() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("盡力答案"))));
        FakeChatModel chatModel = new FakeChatModel(response);
        ChatModelStep step = new ChatModelStep(chatModel, new FakeToolCallingManager(List.of()), options, seed);

        step.forceAnswer(LoopState.init(new LoopRequest("t", "q"))
                .injectCritique("證據不足"));

        assertThat(chatModel.lastPrompt.getInstructions())
                .filteredOn(SystemMessage.class::isInstance)
                .hasSize(1);
    }

    @Test
    void should_return_acted_outcome_when_tool_name_is_unknown() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "id1", "function", "bogus_tool", "{}");
        AssistantMessage toolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(toolMessage)));

        ChatModelStep step = new ChatModelStep(
                new FakeChatModel(response), new UnknownToolThrowingManager(), optionsWithCallbacks(), seed);
        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "q")));

        assertThat(outcome.isFinalCandidate()).isFalse();
        assertThat(outcome.toolNames()).containsExactly("bogus_tool");
        assertThat(outcome.progressLine()).contains("工具名稱無效");
    }

    @Test
    void should_append_feedback_with_valid_names_when_tool_name_is_unknown() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "id1", "function", "bogus_tool", "{}");
        AssistantMessage toolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(toolMessage)));
        FakeChatModel chatModel = new FakeChatModel(response);

        ChatModelStep step = new ChatModelStep(
                chatModel, new UnknownToolThrowingManager(), optionsWithCallbacks(), seed);
        step.step(LoopState.init(new LoopRequest("t", "q")));
        step.step(LoopState.init(new LoopRequest("t", "q")));

        List<String> userTexts = chatModel.lastPrompt.getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(Message::getText)
                .toList();
        assertThat(userTexts).anySatisfy(text -> {
            assertThat(text).contains("bogus_tool");
            assertThat(text).contains("read_service_map");
            assertThat(text).contains("find_call_graph");
        });
    }

    @Test
    void should_propagate_when_illegal_state_is_not_unknown_tool_failure() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "id1", "function", "read_service_map", "{}");
        AssistantMessage toolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(toolMessage)));
        ToolCallingManager failingManager = new ToolCallingManager() {

            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                return List.of();
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                throw new IllegalStateException("boom");
            }
        };

        ChatModelStep step = new ChatModelStep(
                new FakeChatModel(response), failingManager, optionsWithCallbacks(), seed);

        assertThatThrownBy(() -> step.step(LoopState.init(new LoopRequest("t", "q"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void step_throwsEmptyModelResponse_whenNoGenerations() {
        ChatResponse response = new ChatResponse(List.of());
        ChatModelStep step = new ChatModelStep(
                new FakeChatModel(response), new FakeToolCallingManager(List.of()), options, seed);

        assertThatThrownBy(() -> step.step(LoopState.init(new LoopRequest("t", "q"))))
                .isInstanceOf(EmptyModelResponseException.class);
    }

    @Test
    void forceAnswer_throwsEmptyModelResponse_whenNoGenerations() {
        ChatResponse response = new ChatResponse(List.of());
        ChatModelStep step = new ChatModelStep(
                new FakeChatModel(response), new FakeToolCallingManager(List.of()), options, seed);

        assertThatThrownBy(() -> step.forceAnswer(LoopState.init(new LoopRequest("t", "q"))))
                .isInstanceOf(EmptyModelResponseException.class);
    }

    private ChatOptions optionsWithCallbacks() {
        return ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(
                        new StubToolCallback("read_service_map"),
                        new StubToolCallback("find_call_graph")))
                .internalToolExecutionEnabled(false)
                .build();
    }

    private static final class FakeChatModel implements ChatModel {

        private final ChatResponse response;
        private Prompt lastPrompt;

        private FakeChatModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            return response;
        }
    }

    private static final class FakeToolCallingManager implements ToolCallingManager {

        private final List<Message> conversationHistory;

        private FakeToolCallingManager(List<Message> conversationHistory) {
            this.conversationHistory = List.copyOf(conversationHistory);
        }

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            return ToolExecutionResult.builder()
                    .conversationHistory(conversationHistory)
                    .build();
        }
    }

    private static final class UnknownToolThrowingManager implements ToolCallingManager {

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            throw new IllegalStateException("No ToolCallback found for tool name: bogus_tool");
        }
    }

    private static final class StubToolCallback implements ToolCallback {

        private final String name;

        private StubToolCallback(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description(name)
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "{}";
        }
    }

    private static final class FakeLlmRateLimiter implements LlmRateLimiter {

        private int acquired;
        private int recorded;

        @Override
        public RateLimitReservation acquire(Prompt prompt) {
            acquired++;
            return new RateLimitReservation(1);
        }

        @Override
        public RateLimitReservation acquire(String promptText) {
            acquired++;
            return new RateLimitReservation(1);
        }

        @Override
        public void record(RateLimitReservation reservation, ChatResponse response) {
            recorded++;
        }

        @Override
        public void record(RateLimitReservation reservation, String responseText) {
            recorded++;
        }
    }
}
