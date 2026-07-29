package com.java.system.agent.answering.port.out;

/**
 * action adapter 發現 planning tool 註冊或解譯契約缺陷時跨越 answering 邊界的非暫時性失敗
 */
public final class AgentActionContractException extends IllegalStateException {

    public AgentActionContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
