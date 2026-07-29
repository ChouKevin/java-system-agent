package com.java.system.agent.answering.port.in;

/**
 * inbox 可分類的回答執行暫時不可用失敗
 */
public final class AnswerExecutionUnavailableException extends RuntimeException {
    public AnswerExecutionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
