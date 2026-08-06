package com.java.system.agent.answering.application.loop;

/**
 * 同一個 Agent Run 已被另一個 caller 建立且尚未可安全恢復
 */
public final class AgentRunInProgressException extends IllegalStateException {

    public AgentRunInProgressException(String message) {
        super(message);
    }
}
