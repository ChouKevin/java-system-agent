package com.java.system.agent.runtime.application;

/**
 * Terminal reconciliation 遇到尚未產生 terminal state 的 Agent Run
 */
public final class AgentRunTerminalReconciliationException extends IllegalStateException {

    public AgentRunTerminalReconciliationException(String message) {
        super(message);
    }
}
