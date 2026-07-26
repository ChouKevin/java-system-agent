package com.java.system.agent.runtime.domain.run;

/**
 * Agent Run 的 append-only 狀態
 */
public enum AgentRunStatus {
    STARTING,
    RUNNING,
    RESTARTING,
    CONCLUDED
}
