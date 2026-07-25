package com.java.system.agent.ai.loop;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
    void actTurnCapturesSafeToolResultObservation() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "id1", "function", "read_service_map", "{}");
        AssistantMessage toolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "id1", "read_service_map", "tool output")))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(toolMessage)));
        ChatModelStep step = new ChatModelStep(
                new FakeChatModel(response),
                new FakeToolCallingManager(List.of(toolMessage, toolResponse)),
                options,
                seed);

        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "q")));

        ToolResultObservation result = outcome.toolCalls().getFirst().result();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.rawLength()).isEqualTo(11);
        assertThat(result.sha256())
                .isEqualTo("e2a78c7b5a05c0b34cbcac65007d1e2a5765341d79b159797db7baf5fa1ecb13");
        assertThat(result.durationMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(result.summary()).isEmpty();
    }

    @Test
    void actTurnCorrelatesOnlyCurrentResponsesByNameAndRepeatedNameOrder() {
        List<AssistantMessage.ToolCall> toolCalls = List.of(
                new AssistantMessage.ToolCall("id1", "function", "read_service_map", "{}"),
                new AssistantMessage.ToolCall("id2", "function", "find_call_graph", "{}"),
                new AssistantMessage.ToolCall("id3", "function", "read_service_map", "{}"));
        AssistantMessage toolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(toolCalls)
                .build();
        ToolResponseMessage priorResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "old", "find_call_graph", "verified: prior response")))
                .build();
        ToolResponseMessage currentResponse = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse(
                                "id2", "find_call_graph", "verified: current response"),
                        new ToolResponseMessage.ToolResponse(
                                "id1", "read_service_map", "first"),
                        new ToolResponseMessage.ToolResponse(
                                "id3", "read_service_map", "second-result")))
                .build();
        List<Message> history = List.of(
                new UserMessage("previous round"), priorResponse, toolMessage, currentResponse);
        ChatModelStep step = new ChatModelStep(
                new FakeChatModel(new ChatResponse(List.of(new Generation(toolMessage)))),
                new FakeToolCallingManager(history),
                options,
                seed);

        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "q")));

        assertThat(outcome.toolNames())
                .containsExactly("read_service_map", "find_call_graph", "read_service_map");
        assertThat(outcome.toolCalls()).extracting(call -> call.result().rawLength())
                .containsExactly(5, 26, 13);
        assertThat(outcome.toolCalls().get(1).result().summary())
                .isEqualTo("verified: current response");
    }

    @Test
    void actTurnMarksRequestWithoutMatchingCurrentResponseAsFailed() {
        List<AssistantMessage.ToolCall> toolCalls = List.of(
                new AssistantMessage.ToolCall("id1", "function", "read_service_map", "{}"),
                new AssistantMessage.ToolCall("id2", "function", "find_call_graph", "{}"));
        AssistantMessage toolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(toolCalls)
                .build();
        ToolResponseMessage currentResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "id1", "read_service_map", "complete")))
                .build();
        ChatModelStep step = new ChatModelStep(
                new FakeChatModel(new ChatResponse(List.of(new Generation(toolMessage)))),
                new FakeToolCallingManager(List.of(toolMessage, currentResponse)),
                options,
                seed);

        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "q")));

        assertThat(outcome.toolCalls().getFirst().result().status()).isEqualTo("SUCCESS");
        ToolResultObservation missing = outcome.toolCalls().get(1).result();
        assertThat(missing.status()).isEqualTo("FAILED");
        assertThat(missing.failureCode()).isEqualTo("MISSING_TOOL_RESPONSE");
        assertThat(missing.rawLength()).isZero();
        assertThat(missing.sha256()).isEmpty();
        assertThat(missing.summary()).isEmpty();
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
    void reviseTurn_appendsInstructionAsTrailingUserMessage() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("初版回答"))));
        FakeChatModel chatModel = new FakeChatModel(response);
        ChatModelStep step = new ChatModelStep(chatModel, new FakeToolCallingManager(List.of()), options, seed);
        LoopState initialState = LoopState.init(new LoopRequest("t", "q"));

        step.step(initialState);
        step.step(initialState.injectCritique("證據不足"));

        List<Message> instructions = chatModel.lastPrompt.getInstructions();
        Message lastMessage = instructions.getLast();
        assertThat(lastMessage).isInstanceOf(UserMessage.class);
        UserMessage revisionMessage = (UserMessage) lastMessage;
        assertThat(revisionMessage.getText())
                .contains("必須輸出非空完整回答")
                .contains("證據不足");
        assertThat(instructions).filteredOn(SystemMessage.class::isInstance).hasSize(1);
    }

    @Test
    void forceAnswer_withCritique_appendsForceInstructionAsTrailingUserMessage() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("盡力答案"))));
        FakeChatModel chatModel = new FakeChatModel(response);
        ChatModelStep step = new ChatModelStep(chatModel, new FakeToolCallingManager(List.of()), options, seed);

        step.forceAnswer(LoopState.init(new LoopRequest("t", "q"))
                .injectCritique("證據不足"));

        List<Message> instructions = chatModel.lastPrompt.getInstructions();
        Message lastMessage = instructions.getLast();
        assertThat(lastMessage).isInstanceOf(UserMessage.class);
        UserMessage forceMessage = (UserMessage) lastMessage;
        assertThat(forceMessage.getText())
                .contains("根據目前已知資訊直接作答")
                .contains("必須輸出非空完整回答");
        assertThat(instructions.get(instructions.size() - 2)).isInstanceOf(UserMessage.class);
        UserMessage revisionMessage = (UserMessage) instructions.get(instructions.size() - 2);
        assertThat(revisionMessage.getText()).contains("證據不足");
        assertThat(instructions).filteredOn(SystemMessage.class::isInstance).hasSize(1);
    }

    @Test
    void reviseTurn_afterToolHistory_keepsHistoryBeforeTrailingInstruction() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "id1", "function", "read_service_map", "{}");
        AssistantMessage toolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("id1", "read_service_map", "obs")))
                .build();
        List<Message> conversationHistory = List.of(
                new SystemMessage("s"),
                new UserMessage("q"),
                toolMessage,
                toolResponse);
        ChatResponse toolResponseFromModel = new ChatResponse(List.of(new Generation(toolMessage)));
        ChatResponse firstAnswer = new ChatResponse(List.of(new Generation(new AssistantMessage("初版回答"))));
        ChatResponse revisedAnswer = new ChatResponse(List.of(new Generation(new AssistantMessage("修正版"))));
        SequencedFakeChatModel chatModel = new SequencedFakeChatModel(
                List.of(toolResponseFromModel, firstAnswer, revisedAnswer));
        ChatModelStep step = new ChatModelStep(
                chatModel,
                new FakeToolCallingManager(conversationHistory),
                options,
                seed);
        LoopState initialState = LoopState.init(new LoopRequest("t", "q"));

        step.step(initialState);
        step.step(initialState);
        step.step(initialState.injectCritique("證據不足"));

        List<Message> instructions = chatModel.lastPrompt.getInstructions();
        assertThat(instructions).anyMatch(ToolResponseMessage.class::isInstance);
        assertThat(instructions).anyMatch(message -> message instanceof AssistantMessage
                && "初版回答".equals(message.getText()));
        assertThat(instructions).filteredOn(SystemMessage.class::isInstance).hasSize(1);
        assertThat(instructions.getLast()).isInstanceOf(UserMessage.class);
    }

    @Test
    void prompt_normalizesMultipleSystemMessagesToOne() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("回答"))));
        FakeChatModel chatModel = new FakeChatModel(response);
        List<Message> multipleSystemSeed = List.of(
                new SystemMessage("第一段規則"),
                new UserMessage("問題"),
                new SystemMessage("第二段規則"));
        ChatModelStep step = new ChatModelStep(
                chatModel, new FakeToolCallingManager(List.of()), options, multipleSystemSeed);

        step.step(LoopState.init(new LoopRequest("t", "問題")));

        List<Message> instructions = chatModel.lastPrompt.getInstructions();
        List<Message> systemMessages = instructions.stream()
                .filter(SystemMessage.class::isInstance)
                .toList();
        assertThat(systemMessages).hasSize(1);
        assertThat(systemMessages.getFirst().getText())
                .contains("第一段規則")
                .contains("第二段規則");
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
        assertThat(outcome.toolCalls().getFirst().result().status()).isEqualTo("FAILED");
        assertThat(outcome.toolCalls().getFirst().result().failureCode()).isEqualTo("UNKNOWN_TOOL");
        assertThat(outcome.toolCalls().getFirst().result().summary()).isEmpty();
    }

    @Test
    void unknownToolFailureMarksOnlyTheMatchingRequestFailed() {
        List<AssistantMessage.ToolCall> toolCalls = List.of(
                new AssistantMessage.ToolCall("id1", "function", "read_service_map", "{}"),
                new AssistantMessage.ToolCall("id2", "function", "bogus_tool", "{}"),
                new AssistantMessage.ToolCall("id3", "function", "find_call_graph", "{}"));
        AssistantMessage toolMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(toolCalls)
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(toolMessage)));
        ChatModelStep step = new ChatModelStep(
                new FakeChatModel(response), new UnknownToolThrowingManager(), optionsWithCallbacks(), seed);

        StepOutcome outcome = step.step(LoopState.init(new LoopRequest("t", "q")));

        assertThat(outcome.toolCalls()).extracting(call -> call.result().status())
                .containsExactly("PENDING", "FAILED", "PENDING");
        assertThat(outcome.toolCalls()).extracting(call -> call.result().failureCode())
                .containsExactly("", "UNKNOWN_TOOL", "");
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
            this.lastPrompt = prompt.copy();
            return response;
        }
    }

    private static final class SequencedFakeChatModel implements ChatModel {

        private final List<ChatResponse> responses;
        private int responseIndex;
        private Prompt lastPrompt;

        private SequencedFakeChatModel(List<ChatResponse> responses) {
            this.responses = List.copyOf(responses);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt.copy();
            return responses.get(responseIndex++);
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
