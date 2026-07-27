package com.java.system.agent.model.verification;

import com.java.system.agent.runtime.domain.answer.AnswerVerificationMode;
import com.java.system.agent.runtime.port.out.AnswerVerificationContext;
import com.java.system.agent.runtime.port.out.AnswerVerificationResult;

import java.util.Objects;

/**
 * 不呼叫模型而只確認既有 runtime contract 的 verifier 策略
 */
public final class ContractOnlyAnswerVerificationAdapter implements AnswerVerificationStrategy {

    @Override
    public AnswerVerificationMode mode() {
        return AnswerVerificationMode.CONTRACT_ONLY;
    }

    @Override
    public AnswerVerificationResult verify(AnswerVerificationMode mode, AnswerVerificationContext context) {
        Objects.requireNonNull(mode, "answer verification mode must not be null");
        Objects.requireNonNull(context, "answer verification context must not be null");
        if (mode != AnswerVerificationMode.CONTRACT_ONLY) {
            throw new IllegalArgumentException("contract-only verifier received a different mode");
        }
        return new AnswerVerificationResult.ContractAccepted();
    }
}
