package com.java.system.agent.ai.loop;

import java.util.Objects;

/** 一次 tool 呼叫的集中紀錄 */
public record ToolCallRecord(String name, String arguments, ToolResultObservation result) {

    public ToolCallRecord {
        result = Objects.requireNonNullElseGet(result, ToolResultObservation::pending);
    }

    public ToolCallRecord(String name, String arguments) {
        this(name, arguments, ToolResultObservation.pending());
    }

    public static ToolCallRecord of(String name) {
        return new ToolCallRecord(name, "{}");
    }

    public ToolCallRecord withResult(ToolResultObservation completedResult) {
        return new ToolCallRecord(name, arguments, completedResult);
    }
}
