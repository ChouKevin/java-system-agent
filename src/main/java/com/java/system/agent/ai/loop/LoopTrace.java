package com.java.system.agent.ai.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** loop run 完成後產生的單一追蹤樹 */
public record LoopTrace(String traceId, String role, String finalAnswer,
                        boolean accepted, List<LoopStep> steps, List<ToolCallRecord> toolCalls,
                        Map<String, String> metadata, TerminationReason terminationReason) {

    public LoopTrace {
        steps = List.copyOf(steps);
        toolCalls = List.copyOf(toolCalls);
        metadata = Map.copyOf(metadata);
        terminationReason = Objects.requireNonNull(terminationReason, "terminationReason must not be null");
    }

    public LoopTrace(String traceId, String role, String finalAnswer,
                     boolean accepted, List<LoopStep> steps, List<ToolCallRecord> toolCalls,
                     Map<String, String> metadata) {
        this(traceId, role, finalAnswer, accepted, steps, toolCalls, metadata,
                acceptedLegacyTerminationReason(accepted));
    }

    public LoopTrace(String traceId, String role, String finalAnswer,
                     boolean accepted, List<LoopStep> steps, List<ToolCallRecord> toolCalls) {
        this(traceId, role, finalAnswer, accepted, steps, toolCalls, Map.of(),
                acceptedLegacyTerminationReason(accepted));
    }

    public LoopTrace(String traceId, String role, String finalAnswer,
                     boolean accepted, List<LoopStep> steps, List<ToolCallRecord> toolCalls,
                     TerminationReason terminationReason) {
        this(traceId, role, finalAnswer, accepted, steps, toolCalls, Map.of(), terminationReason);
    }

    public LoopTrace(String finalAnswer, boolean accepted, List<LoopStep> steps, List<ToolCallRecord> toolCalls) {
        this(UUID.randomUUID().toString(), "loop", finalAnswer, accepted, steps, toolCalls, Map.of(),
                acceptedLegacyTerminationReason(accepted));
    }

    public LoopTrace withMetadata(Map<String, String> metadata) {
        return new LoopTrace(traceId, role, finalAnswer,
                accepted, steps, toolCalls, metadata, terminationReason);
    }

    public int iterationCount() {
        return steps.size();
    }

    public long rejectionCount() {
        return steps.stream()
                .filter(LoopStep::rejected)
                .count();
    }

    public int totalTurns() {
        return steps.size() + steps.stream()
                .flatMap(step -> step.childTraces().stream())
                .mapToInt(LoopTrace::totalTurns)
                .sum();
    }

    public long totalTokens() {
        long ownTokens = steps.stream()
                .mapToLong(step -> step.metrics().promptTokens() + step.metrics().completionTokens())
                .sum();
        long childTokens = steps.stream()
                .flatMap(step -> step.childTraces().stream())
                .mapToLong(LoopTrace::totalTokens)
                .sum();
        return ownTokens + childTokens;
    }

    /** 去除未驗證警示前綴後的原始答案，供工具輸出等機器情境使用。 */
    public String plainFinalAnswer() {
        String answer = Objects.toString(finalAnswer, "");
        if (answer.startsWith(AgentLoopRunner.UNVERIFIED_NOTE)) {
            return answer.substring(AgentLoopRunner.UNVERIFIED_NOTE.length());
        }
        return answer;
    }

    public String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private static TerminationReason acceptedLegacyTerminationReason(boolean accepted) {
        Assert.isTrue(accepted, "legacy LoopTrace constructor requires an accepted trace");
        return TerminationReason.ACCEPTED;
    }
}
