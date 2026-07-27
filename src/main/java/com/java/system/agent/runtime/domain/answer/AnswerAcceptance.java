package com.java.system.agent.runtime.domain.answer;

import com.java.system.agent.runtime.domain.run.RunOutcome;

import java.util.Objects;
import java.util.Optional;

/**
 * 已接受回答的持久化驗證結果與唯一終態映射
 */
public record AnswerAcceptance(AnswerVerificationBasis verificationBasis, Optional<AnswerVerdict> verdict) {

    public AnswerAcceptance {
        Objects.requireNonNull(verificationBasis, "answer verification basis must not be null");
        Objects.requireNonNull(verdict, "answer acceptance verdict must not be null");
        if (verificationBasis == AnswerVerificationBasis.LLM && verdict.isEmpty()) {
            throw new IllegalArgumentException("LLM acceptance requires a verdict");
        }
        if (verificationBasis == AnswerVerificationBasis.CONTRACT_ONLY && verdict.isPresent()) {
            throw new IllegalArgumentException("contract-only acceptance cannot carry a verdict");
        }
        if (verdict.isPresent() && verdict.orElseThrow().disposition() == AnswerDisposition.REJECTED) {
            throw new IllegalArgumentException("answer acceptance requires an accepted verdict");
        }
    }

    public static AnswerAcceptance llm(AnswerVerdict verdict) {
        return new AnswerAcceptance(AnswerVerificationBasis.LLM, Optional.of(Objects.requireNonNull(
                verdict, "answer verdict must not be null")));
    }

    public static AnswerAcceptance contractOnly() {
        return new AnswerAcceptance(AnswerVerificationBasis.CONTRACT_ONLY, Optional.empty());
    }

    public RunOutcome expectedOutcome() {
        return switch (verificationBasis) {
            case CONTRACT_ONLY -> RunOutcome.COMPLETED;
            case LLM -> switch (verdict.orElseThrow().disposition()) {
                case ACCEPTED_COMPLETE -> RunOutcome.COMPLETED;
                case ACCEPTED_INCONCLUSIVE -> RunOutcome.INCONCLUSIVE;
                case REJECTED -> throw new IllegalStateException("rejected verdict cannot be accepted");
            };
        };
    }
}
