package com.java.system.agent.ai.loop;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 一次 outer-controlled model turn:tool 由本類別透過 ToolCallingManager 執行 */
public final class ChatModelStep implements StepExecutor {

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final ChatOptions options;
    private final List<Message> working;
    private final LoopTraceCollector collector;
    private int appendedCritiques = 0;

    public ChatModelStep(ChatModel chatModel, ToolCallingManager toolCallingManager,
                         ChatOptions options, List<Message> seedMessages) {
        this(chatModel, toolCallingManager, options, seedMessages, new LoopTraceCollector());
    }

    public ChatModelStep(ChatModel chatModel, ToolCallingManager toolCallingManager,
                         ChatOptions options, List<Message> seedMessages,
                         LoopTraceCollector collector) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.options = options;
        this.working = new ArrayList<>(seedMessages);
        this.collector = collector;
    }

    @Override
    public StepOutcome step(LoopState state) {
        appendNewCritiques(state);

        Prompt prompt = new Prompt(working, options);
        ChatResponse response = chatModel.call(prompt);
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
            return StepOutcome.acted("⚙️ 已查詢: " + String.join(", ", names), toolCalls, collector.drain());
        }

        working.add(output);
        return StepOutcome.finalCandidate("⚙️ 整理回覆…",
                new Candidate(Objects.toString(output.getText(), "")));
    }

    @Override
    public Candidate forceAnswer(LoopState state) {
        appendNewCritiques(state);
        List<Message> forced = new ArrayList<>(working);
        forced.add(new SystemMessage("根據目前已知資訊直接作答，不要再呼叫任何工具，只用業務語言"));
        ChatOptions noTools = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        AssistantMessage output = chatModel.call(new Prompt(forced, noTools)).getResult().getOutput();
        return new Candidate(Objects.toString(output.getText(), ""));
    }

    private void appendNewCritiques(LoopState state) {
        List<String> critiques = state.critiques();
        for (int index = appendedCritiques; index < critiques.size(); index++) {
            working.add(new SystemMessage("[自我檢查] " + critiques.get(index)));
        }
        appendedCritiques = critiques.size();
    }
}
