package com.java.system.agent.answering.port.in;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.domain.scope.RevisionVector;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link AnswerQuestionUseCase} 的輸出：唯一驗證 action loop 的最終結果
 */
public record AnswerQuestionResult(
        AnalysisRunId runId,
        RunOutcome outcome,
        String responseText,
        Optional<AnswerDocument> answerDocument,
        RunResponseKind responseKind,
        Optional<AnswerVerificationBasis> verificationBasis,
        RevisionVector finalRevisions) {

    public AnswerQuestionResult {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(outcome, "analysis run outcome must not be null");
        Objects.requireNonNull(responseText, "answer response text must not be null");
        Objects.requireNonNull(answerDocument, "answer document must not be null");
        Objects.requireNonNull(responseKind, "answer response kind must not be null");
        Objects.requireNonNull(verificationBasis, "answer verification basis must not be null");
        Objects.requireNonNull(finalRevisions, "final revisions must not be null");
        if (responseText.isBlank()) {
            throw new IllegalArgumentException("answer response text must not be blank");
        }
        switch (responseKind) {
            case ANSWER -> {
                if (answerDocument.isEmpty() || verificationBasis.isEmpty()
                        || !responseText.equals(answerDocument.orElseThrow().renderParagraphs())
                        || outcome != RunOutcome.COMPLETED && outcome != RunOutcome.INCONCLUSIVE
                        || verificationBasis.orElseThrow() == AnswerVerificationBasis.CONTRACT_ONLY
                        && outcome != RunOutcome.COMPLETED) {
                    throw new IllegalArgumentException("answer response must retain its document and verification basis");
                }
            }
            case CLARIFICATION -> {
                if (answerDocument.isPresent() || verificationBasis.isPresent() || outcome != RunOutcome.INCONCLUSIVE) {
                    throw new IllegalArgumentException("clarification response must be an inconclusive non-answer");
                }
            }
            case RUNTIME_NOTICE -> {
                if (answerDocument.isPresent() || verificationBasis.isPresent() || outcome == RunOutcome.COMPLETED) {
                    throw new IllegalArgumentException("runtime notice must not retain answer verification content");
                }
            }
        }
    }
}
