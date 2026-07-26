package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RevisionVector;

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
        RevisionVector finalRevisions) {

    public AnswerQuestionResult {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(outcome, "analysis run outcome must not be null");
        Objects.requireNonNull(responseText, "answer response text must not be null");
        Objects.requireNonNull(answerDocument, "answer document must not be null");
        Objects.requireNonNull(finalRevisions, "final revisions must not be null");
        if (responseText.isBlank()) {
            throw new IllegalArgumentException("answer response text must not be blank");
        }
        if (outcome == RunOutcome.COMPLETED && answerDocument.isEmpty()) {
            throw new IllegalArgumentException("completed answer result must contain an answer document");
        }
        if ((outcome == RunOutcome.FAILED || outcome == RunOutcome.CANCELLED) && answerDocument.isPresent()) {
            throw new IllegalArgumentException("failed or cancelled answer result must not contain an answer document");
        }
        if (answerDocument.isPresent()
                && !responseText.equals(answerDocument.orElseThrow().renderParagraphs())) {
            throw new IllegalArgumentException("answer response text must render the answer document exactly");
        }
    }
}
