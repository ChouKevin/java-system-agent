package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.answer.AnswerVerdict;

import java.util.Objects;

/**
 * 回答 verifier 的模式化結果
 */
public sealed interface AnswerVerificationResult permits AnswerVerificationResult.LlmVerdict,
        AnswerVerificationResult.ContractAccepted {

    record LlmVerdict(AnswerVerdict verdict) implements AnswerVerificationResult {
        public LlmVerdict {
            Objects.requireNonNull(verdict, "answer verdict must not be null");
        }
    }

    record ContractAccepted() implements AnswerVerificationResult {
    }
}
