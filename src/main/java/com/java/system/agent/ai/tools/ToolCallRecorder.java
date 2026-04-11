package com.java.system.agent.ai.tools;

import java.util.Set;

/**
 * Minimal hook for observing tool calls executed by ToolCallingManager.
 */
public interface ToolCallRecorder {

    void record(String toolName, String argumentsJson);

    default String getSummaryForTools(Set<String> toolNames, String title) {
        return "";
    }
}
