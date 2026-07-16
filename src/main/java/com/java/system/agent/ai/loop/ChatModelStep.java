package com.java.system.agent.ai.loop;

import com.java.system.agent.ai.tools.ToolResultSummarizer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 一次 outer-controlled model turn:tool 由本類別透過 ToolCallingManager 執行 */
public final class ChatModelStep implements StepExecutor {

    private static final String UNKNOWN_TOOL_MESSAGE_PREFIX = "No ToolCallback found for tool name: ";

    private static final String REVISION_INSTRUCTION = """
            請重新輸出修正後的完整最終回答。
            必須輸出非空完整回答，不得只回覆「好的」、「已修正」或其他確認語。
            保留原回答中與使用者問題相關的業務結論與分析，只修正審查指出的問題，不要刪除或大幅縮短原回答。
            若問題是洩漏資料表、欄位、狀態碼、類別或方法名稱，請將該部分改寫成業務語言，不要直接刪除相關業務意義。
            若原回答為空，請根據目前對話與工具結果整理出可交付的完整回答。
            只輸出修正後的最終回答，不要說明修正過程，也不要輸出任何 meta 訊息。

            審查指出的問題：%s
            """;

    private static final String FORCE_ANSWER_INSTRUCTION = """
            根據目前已知資訊直接作答，不要再呼叫任何工具，只用業務語言。
            必須輸出非空完整回答，不得只回覆「好的」、「已修正」或其他確認語。
            只輸出可交付的最終回答，不要說明限制或修正過程。
            """;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final ChatOptions options;
    private final List<Message> working;
    private final LoopTraceCollector collector;
    private final LlmRateLimiter rateLimiter;
    private final ToolResultSummarizer toolResultSummarizer = new ToolResultSummarizer();
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
        AssistantMessage output = outputOf(response);
        StepMetrics metrics = metricsSince(startMillis, response);

        if (response.hasToolCalls()) {
            List<ToolCallRecord> toolCalls = output.getToolCalls().stream()
                    .map(toolCall -> new ToolCallRecord(toolCall.name(), toolCall.arguments()))
                    .toList();
            ToolExecutionResult result;
            long toolStartNanos = System.nanoTime();
            try {
                result = toolCallingManager.executeToolCalls(prompt, response);
            } catch (IllegalStateException exception) {
                Optional<String> unknownToolName = unknownToolName(exception);
                if (unknownToolName.isEmpty()) {
                    throw exception;
                }
                working.add(new UserMessage(unknownToolFeedback(unknownToolName.get())));
                List<ToolCallRecord> failedToolCalls = failedToolCalls(
                        toolCalls, unknownToolName.get(), elapsedMillis(toolStartNanos));
                return StepOutcome.acted("⚙️ 工具名稱無效，已提示模型改用正確名稱",
                        failedToolCalls, collector.drain(), metrics);
            }
            long toolDurationMillis = elapsedMillis(toolStartNanos);
            working.clear();
            working.addAll(result.conversationHistory());
            List<ToolCallRecord> completedToolCalls = completedToolCalls(
                    toolCalls, result, toolDurationMillis);
            List<String> names = toolCalls.stream()
                    .map(ToolCallRecord::name)
                    .toList();
            return StepOutcome.acted("⚙️ 已查詢: " + String.join(", ", names),
                    completedToolCalls, collector.drain(), metrics);
        }

        working.add(output);
        return StepOutcome.finalCandidate("⚙️ 整理回覆…",
                new Candidate(Objects.toString(output.getText(), "")), metrics);
    }

    @Override
    public Candidate forceAnswer(LoopState state) {
        appendNewCritiques(state);
        List<Message> forced = new ArrayList<>(working);
        forced.add(new UserMessage(FORCE_ANSWER_INSTRUCTION));
        ChatOptions noTools = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = promptFor(forced, noTools);
        RateLimitReservation reservation = rateLimiter.acquire(prompt);
        ChatResponse response = chatModel.call(prompt);
        rateLimiter.record(reservation, response);
        AssistantMessage output = outputOf(response);
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
            working.add(new UserMessage(REVISION_INSTRUCTION.formatted(critiques.get(index))));
        }
        appendedCritiques = critiques.size();
    }

    private Optional<String> unknownToolName(IllegalStateException exception) {
        String message = Objects.toString(exception.getMessage(), "");
        if (!message.startsWith(UNKNOWN_TOOL_MESSAGE_PREFIX)) {
            return Optional.empty();
        }
        String toolName = message.substring(UNKNOWN_TOOL_MESSAGE_PREFIX.length()).strip();
        return StringUtils.hasText(toolName) ? Optional.of(toolName) : Optional.empty();
    }

    private List<ToolCallRecord> completedToolCalls(
            List<ToolCallRecord> requested,
            ToolExecutionResult result,
            long durationMillis) {
        Map<String, Deque<ToolResponseMessage.ToolResponse>> responsesByName = new LinkedHashMap<>();
        for (ToolResponseMessage.ToolResponse response : currentToolResponses(result)) {
            String responseName = Objects.toString(response.name(), "");
            responsesByName.computeIfAbsent(responseName, ignored -> new ArrayDeque<>())
                    .addLast(response);
        }
        List<ToolCallRecord> completed = new ArrayList<>();
        for (ToolCallRecord request : requested) {
            Deque<ToolResponseMessage.ToolResponse> matchingResponses = responsesByName.get(request.name());
            if (CollectionUtils.isEmpty(matchingResponses)) {
                completed.add(request.withResult(
                        ToolResultObservation.failed("MISSING_TOOL_RESPONSE", durationMillis)));
                continue;
            }
            ToolResponseMessage.ToolResponse response = matchingResponses.removeFirst();
            String rawOutput = Objects.toString(response.responseData(), "");
            ToolResultObservation observation = toolResultSummarizer.summarize(
                    request.name(), rawOutput, durationMillis);
            completed.add(request.withResult(observation));
        }
        return List.copyOf(completed);
    }

    private List<ToolResponseMessage.ToolResponse> currentToolResponses(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        if (CollectionUtils.isEmpty(history)) {
            return List.of();
        }
        Message latestMessage = history.getLast();
        if (!(latestMessage instanceof ToolResponseMessage currentResponse)) {
            return List.of();
        }
        return List.copyOf(currentResponse.getResponses());
    }

    private List<ToolCallRecord> failedToolCalls(
            List<ToolCallRecord> requested, String unknownToolName, long durationMillis) {
        return requested.stream()
                .map(request -> unknownToolName.equals(request.name())
                        ? request.withResult(ToolResultObservation.failed("UNKNOWN_TOOL", durationMillis))
                        : request)
                .toList();
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private String unknownToolFeedback(String unknownToolName) {
        List<String> validNames = validToolNames();
        String available = CollectionUtils.isEmpty(validNames)
                ? "（本回合沒有可用工具）"
                : String.join(", ", validNames);
        return "⚠️ 工具呼叫失敗:找不到名為 [" + unknownToolName + "] 的工具\n"
                + "可用的工具名稱:" + available + "\n"
                + "請改用正確的工具名稱重新呼叫;若不需要工具，請直接以業務語言作答";
    }

    private List<String> validToolNames() {
        if (options instanceof ToolCallingChatOptions toolOptions) {
            return toolOptions.getToolCallbacks().stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .toList();
        }
        return List.of();
    }

    private AssistantMessage outputOf(ChatResponse response) {
        if (Objects.isNull(response) || Objects.isNull(response.getResult())
                || Objects.isNull(response.getResult().getOutput())) {
            throw new EmptyModelResponseException("模型未回傳任何內容，可能被安全過濾攔截");
        }
        return response.getResult().getOutput();
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
