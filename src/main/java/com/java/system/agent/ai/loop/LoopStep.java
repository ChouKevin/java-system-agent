package com.java.system.agent.ai.loop;

import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 一次 loop turn 的可追蹤摘要 */
public record LoopStep(int index, String summary, List<String> toolNames,
                       Verdict verdict, List<LoopTrace> childTraces, StepMetrics metrics,
                       String candidateAnswer, Instant createdAt) {

    public LoopStep {
        toolNames = List.copyOf(toolNames);
        childTraces = List.copyOf(childTraces);
        metrics = Objects.requireNonNullElse(metrics, StepMetrics.none());
        candidateAnswer = Objects.toString(candidateAnswer, "");
        createdAt = Objects.requireNonNullElse(createdAt, Instant.EPOCH);
    }

    public LoopStep(int index, String summary, List<String> toolNames,
                    Verdict verdict, List<LoopTrace> childTraces, StepMetrics metrics,
                    String candidateAnswer) {
        this(index, summary, toolNames, verdict, childTraces, metrics,
                candidateAnswer, Instant.now());
    }

    public LoopStep(int index, String summary, List<String> toolNames, Verdict verdict) {
        this(index, summary, toolNames, verdict, List.of(), StepMetrics.none(), "");
    }

    public LoopStep(int index, String summary, List<String> toolNames,
                    Verdict verdict, List<LoopTrace> childTraces) {
        this(index, summary, toolNames, verdict, childTraces, StepMetrics.none(), "");
    }

    public LoopStep(int index, String summary, List<String> toolNames,
                    Verdict verdict, List<LoopTrace> childTraces, StepMetrics metrics) {
        this(index, summary, toolNames, verdict, childTraces, metrics, "");
    }

    public boolean madeToolCalls() {
        return !CollectionUtils.isEmpty(toolNames);
    }

    public boolean rejected() {
        return Objects.nonNull(verdict) && !verdict.accepted();
    }
}
