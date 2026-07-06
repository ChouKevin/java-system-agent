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
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

        ChatModelStep step = new ChatModelStep(chatModel, new FakeToolCallingManager(List.of()), options, seed);

        Candidate answer = step.forceAnswer(LoopState.init(new LoopRequest("t", "q"))
                .injectCritique("證據不足"));

        assertThat(answer.answer()).isEqualTo("盡力答案");
    }

    private static final class FakeChatModel implements ChatModel {

        private final ChatResponse response;

        private FakeChatModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
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
}
