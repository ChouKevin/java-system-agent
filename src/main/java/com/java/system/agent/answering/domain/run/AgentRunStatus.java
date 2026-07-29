package com.java.system.agent.answering.domain.run;

/**
 * Agent Run 的 append-only 狀態
 */
public enum AgentRunStatus {
    STARTING,
    RUNNING,
    RESTARTING,
    CONCLUDED
}
