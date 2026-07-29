package com.java.system.agent.answering.port.in;

/**
 * inbox 可分類的回答執行整合契約失敗
 */
public final class AnswerExecutionContractException extends IllegalStateException {
    private final AnswerExecutionContractFailure failure;

    public AnswerExecutionContractException(String message) {
        this(AnswerExecutionContractFailure.GENERAL_INTEGRATION_CONTRACT, message, null);
    }

    public AnswerExecutionContractException(String message, Throwable cause) {
        this(AnswerExecutionContractFailure.GENERAL_INTEGRATION_CONTRACT, message, cause);
    }

    public AnswerExecutionContractException(
            AnswerExecutionContractFailure failure,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failure = java.util.Objects.requireNonNull(failure, "answer execution contract failure must not be null");
    }

    public AnswerExecutionContractFailure failure() {
        return failure;
    }
}
