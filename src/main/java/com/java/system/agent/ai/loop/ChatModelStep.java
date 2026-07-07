package com.java.system.agent.ai.loop;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 一次 outer-controlled model turn:tool 由本類別透過 ToolCallingManager 執行 */
public final class ChatModelStep implements StepExecutor {

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final ChatOptions options;
    private final List<Message> working;
    private final LoopTraceCollector collector;
    private final LlmRateLimiter rateLimiter;
    private int appendedCritiques = 0;

    public ChatModelStep(ChatModel chatModel, ToolCallingManager toolCallingManager,
                         ChatOptions options, List<Message> seedMessages) {
        this(chatModel, toolCallingManager, options, seedMessages, new LoopTraceCollector());
    }

    public ChatModelStep(ChatModel chatModel, ToolCallingManager toolCallingManager,
                         ChatOptions options, List<Message> seedMessages,
                         LoopTraceCollector collector) {
        this(chatModel, toolCallingManager, options, seedMessages, collector, LlmRateLimiter.NOOP);
    }

    public ChatModelStep(ChatModel chatModel, ToolCallingManager toolCallingManager,
                         ChatOptions options, List<Message> seedMessages,
                         LoopTraceCollector collector, LlmRateLimiter rateLimiter) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.options = options;
        this.working = new ArrayList<>(seedMessages);
        this.collector = collector;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public StepOutcome step(LoopState state) {
        appendNewCritiques(state);

        Prompt prompt = promptFor(working, options);
        RateLimitReservation reservation = rateLimiter.acquire(prompt);
        long startMillis = System.currentTimeMillis();
        ChatResponse response = chatModel.call(prompt);
        rateLimiter.record(reservation, response);
        StepMetrics metrics = metricsSince(startMillis, response);
        AssistantMessage output = response.getResult().getOutput();

        if (response.hasToolCalls()) {
            List<ToolCallRecord> toolCalls = output.getToolCalls().stream()
                    .map(toolCall -> new ToolCallRecord(toolCall.name(), toolCall.arguments()))
                    .toList();
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            working.clear();
            working.addAll(result.conversationHistory());
            List<String> names = toolCalls.stream()
                    .map(ToolCallRecord::name)
                    .toList();
            return StepOutcome.acted("⚙️ 已查詢: " + String.join(", ", names),
                    toolCalls, collector.drain(), metrics);
        }

        working.add(output);
        return StepOutcome.finalCandidate("⚙️ 整理回覆…",
                new Candidate(Objects.toString(output.getText(), "")), metrics);
    }

    @Override
    public Candidate forceAnswer(LoopState state) {
        appendNewCritiques(state);
        List<Message> forced = new ArrayList<>(working);
        forced.add(new SystemMessage("根據目前已知資訊直接作答，不要再呼叫任何工具，只用業務語言"));
        ChatOptions noTools = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = promptFor(forced, noTools);
        RateLimitReservation reservation = rateLimiter.acquire(prompt);
        ChatResponse response = chatModel.call(prompt);
        rateLimiter.record(reservation, response);
        AssistantMessage output = response.getResult().getOutput();
        return new Candidate(Objects.toString(output.getText(), ""));
    }

    private Prompt promptFor(List<Message> messages, ChatOptions promptOptions) {
        return new Prompt(messagesWithSingleSystemMessage(messages), promptOptions);
    }

    private List<Message> messagesWithSingleSystemMessage(List<Message> messages) {
        List<SystemMessage> systemMessages = messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .toList();
        if (systemMessages.size() <= 1) {
            return messages;
        }

        String systemText = systemMessages.stream()
                .map(Message::getText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n"));
        List<Message> normalized = new ArrayList<>();
        normalized.add(new SystemMessage(systemText));
        messages.stream()
                .filter(message -> !(message instanceof SystemMessage))
                .forEach(normalized::add);
        return normalized;
    }

    private void appendNewCritiques(LoopState state) {
        List<String> critiques = state.critiques();
        for (int index = appendedCritiques; index < critiques.size(); index++) {
            working.add(new SystemMessage("[自我檢查] " + critiques.get(index)));
        }
        appendedCritiques = critiques.size();
    }

    private StepMetrics metricsSince(long startMillis, ChatResponse response) {
        long durationMillis = System.currentTimeMillis() - startMillis;
        ChatResponseMetadata metadata = response.getMetadata();
        if (Objects.isNull(metadata)) {
            return StepMetrics.of(durationMillis, null);
        }
        return StepMetrics.of(durationMillis, metadata.getUsage());
    }
}
