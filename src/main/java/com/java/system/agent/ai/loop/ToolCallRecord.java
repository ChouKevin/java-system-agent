package com.java.system.agent.ai.loop;

/** 一次 tool 呼叫的集中紀錄 */
public record ToolCallRecord(String name, String arguments) {

    public static ToolCallRecord of(String name) {
        return new ToolCallRecord(name, "{}");
    }
}
