package com.java.system.agent.ai.loop;

import java.util.List;
import java.util.Objects;

/** 一次 model turn 的產出:act 或 final */
public record StepOutcome(String progressLine, Candidate candidate,
                          List<ToolCallRecord> toolCalls, List<LoopTrace> childTraces) {

    public StepOutcome {
        toolCalls = List.copyOf(toolCalls);
        childTraces = List.copyOf(childTraces);
    }

    public static StepOutcome acted(String progressLine, List<ToolCallRecord> toolCalls) {
        return acted(progressLine, toolCalls, List.of());
    }

    public static StepOutcome acted(String progressLine, List<ToolCallRecord> toolCalls,
                                    List<LoopTrace> childTraces) {
        return new StepOutcome(progressLine, null, toolCalls, childTraces);
    }

    public static StepOutcome finalCandidate(String progressLine, Candidate candidate) {
        return new StepOutcome(progressLine, candidate, List.of(), List.of());
    }

    public boolean isFinalCandidate() {
        return Objects.nonNull(candidate);
    }

    public List<String> toolNames() {
        return toolCalls.stream()
                .map(ToolCallRecord::name)
                .toList();
    }
}
