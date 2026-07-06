package com.java.system.agent.ai.loop;

import java.util.List;
import java.util.Objects;

/** 一次 model turn 的產出:act 或 final */
public record StepOutcome(String progressLine, Candidate candidate,
                          List<ToolCallRecord> toolCalls, List<LoopTrace> childTraces,
                          StepMetrics metrics) {

    public StepOutcome {
        toolCalls = List.copyOf(toolCalls);
        childTraces = List.copyOf(childTraces);
        metrics = Objects.requireNonNullElse(metrics, StepMetrics.none());
    }

    public StepOutcome(String progressLine, Candidate candidate,
                       List<ToolCallRecord> toolCalls, List<LoopTrace> childTraces) {
        this(progressLine, candidate, toolCalls, childTraces, StepMetrics.none());
    }

    public static StepOutcome acted(String progressLine, List<ToolCallRecord> toolCalls) {
        return acted(progressLine, toolCalls, List.of(), StepMetrics.none());
    }

    public static StepOutcome acted(String progressLine, List<ToolCallRecord> toolCalls,
                                    List<LoopTrace> childTraces) {
        return acted(progressLine, toolCalls, childTraces, StepMetrics.none());
    }

    public static StepOutcome acted(String progressLine, List<ToolCallRecord> toolCalls,
                                    List<LoopTrace> childTraces, StepMetrics metrics) {
        return new StepOutcome(progressLine, null, toolCalls, childTraces, metrics);
    }

    public static StepOutcome finalCandidate(String progressLine, Candidate candidate) {
        return finalCandidate(progressLine, candidate, StepMetrics.none());
    }

    public static StepOutcome finalCandidate(String progressLine, Candidate candidate, StepMetrics metrics) {
        return new StepOutcome(progressLine, candidate, List.of(), List.of(), metrics);
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
