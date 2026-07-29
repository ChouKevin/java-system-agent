package com.java.system.agent.answering.port.out;

/**
 * 回答 verifier 暫時無法使用時拋出的例外
 */
public final class AnswerVerificationUnavailableException extends RuntimeException {
    public AnswerVerificationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
