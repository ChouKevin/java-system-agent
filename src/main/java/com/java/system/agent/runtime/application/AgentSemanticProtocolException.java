package com.java.system.agent.runtime.application;

/**
 * Agent semantic 回應違反 runtime-issued opaque handle 協定時的失敗
 */
public final class AgentSemanticProtocolException extends RuntimeException {

    public AgentSemanticProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
