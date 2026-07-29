package com.java.system.agent.answering.application.validation;

import java.util.Objects;

/** 回答文件引用的 answering 契約不成立時的型別化例外 */
public final class AnswerDocumentContractException extends IllegalArgumentException {

    private final ActionRejectionCode rejectionCode;

    public AnswerDocumentContractException(ActionRejectionCode rejectionCode, String message) {
        super(message);
        this.rejectionCode = Objects.requireNonNull(rejectionCode, "answer document rejection code must not be null");
    }

    public ActionRejectionCode rejectionCode() {
        return rejectionCode;
    }
}
