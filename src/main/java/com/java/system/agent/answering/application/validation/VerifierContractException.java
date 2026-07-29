package com.java.system.agent.answering.application.validation;

/** 驗證器收到結構不合法的 verdict 合約時拋出的例外 */
public final class VerifierContractException extends IllegalArgumentException {
    public VerifierContractException(String message) { super(message); }
}
