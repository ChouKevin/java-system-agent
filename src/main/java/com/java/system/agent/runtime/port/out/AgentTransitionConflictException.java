package com.java.system.agent.runtime.port.out;

/**
 * 原子建立或 revision CAS 未取得 Agent Run transition 所有權
 */
public final class AgentTransitionConflictException extends IllegalStateException {

    public AgentTransitionConflictException(String message) {
        super(message);
    }
}
