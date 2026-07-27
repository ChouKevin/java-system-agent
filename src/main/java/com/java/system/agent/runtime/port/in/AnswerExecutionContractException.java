package com.java.system.agent.runtime.port.in;

/**
 * inbox 可分類的回答執行整合契約失敗
 */
public final class AnswerExecutionContractException extends IllegalStateException {
    public AnswerExecutionContractException(String message) {
        super(message);
    }

    public AnswerExecutionContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
