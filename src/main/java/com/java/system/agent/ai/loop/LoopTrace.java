package com.java.system.agent.ai.loop;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/** loop run 完成後產生的單一追蹤樹 */
public record LoopTrace(String traceId, String role, String finalAnswer,
                        boolean accepted, List<LoopStep> steps, List<ToolCallRecord> toolCalls) {

    public LoopTrace {
        steps = List.copyOf(steps);
        toolCalls = List.copyOf(toolCalls);
    }

    public LoopTrace(String finalAnswer, boolean accepted, List<LoopStep> steps, List<ToolCallRecord> toolCalls) {
        this(UUID.randomUUID().toString(), "loop", finalAnswer, accepted, steps, toolCalls);
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

    public String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }
}
