package com.java.system.agent.answering.port.out;

/**
 * 回答 verifier 違反 answering 合約時拋出的例外
 */
public final class AnswerVerificationContractException extends IllegalStateException {
    public AnswerVerificationContractException(String message) {
        super(message);
    }
}
