package com.java.system.agent.runtime.port.out;

/**
 * 取得 Agent 動作時發生 transport 層失敗
 */
public final class AgentActionTransportException extends RuntimeException {

    public AgentActionTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
