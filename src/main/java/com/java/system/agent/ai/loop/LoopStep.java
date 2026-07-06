package com.java.system.agent.ai.loop;

import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Objects;

/** 一次 loop turn 的可追蹤摘要 */
public record LoopStep(int index, String summary, List<String> toolNames,
                       Verdict verdict, List<LoopTrace> childTraces) {

    public LoopStep {
        toolNames = List.copyOf(toolNames);
        childTraces = List.copyOf(childTraces);
    }

    public LoopStep(int index, String summary, List<String> toolNames, Verdict verdict) {
        this(index, summary, toolNames, verdict, List.of());
    }

    public boolean madeToolCalls() {
        return !CollectionUtils.isEmpty(toolNames);
    }

    public boolean rejected() {
        return Objects.nonNull(verdict) && !verdict.accepted();
    }
}
