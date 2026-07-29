package com.java.system.agent.answering.application.state;

/**
 * Agent transition 未能以候選狀態原子提交
 */
public final class AgentTransitionCommitException extends IllegalStateException {
    public AgentTransitionCommitException(String message) {
        super(message);
    }

    public AgentTransitionCommitException(String message, Throwable cause) {
        super(message, cause);
    }
}
