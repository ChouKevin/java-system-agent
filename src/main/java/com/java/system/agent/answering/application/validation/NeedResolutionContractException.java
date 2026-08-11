package com.java.system.agent.answering.application.validation;

import java.util.Objects;

/**
 * QuestionPlan 解析權限契約不成立時的型別化例外
 */
public final class NeedResolutionContractException extends IllegalArgumentException {

    private static final String SAFE_DESCRIPTION = "question plan resolution contract rejected";

    private final ActionRejectionCode rejectionCode;

    public NeedResolutionContractException(ActionRejectionCode rejectionCode) {
        super(SAFE_DESCRIPTION);
        this.rejectionCode = Objects.requireNonNull(rejectionCode, "need resolution rejection code must not be null");
    }

    public ActionRejectionCode rejectionCode() {
        return rejectionCode;
    }
}
