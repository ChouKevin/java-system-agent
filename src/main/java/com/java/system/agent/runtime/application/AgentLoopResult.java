package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RevisionVector;

import java.util.Objects;
import java.util.Optional;

/**
 * Validated Agent Loop 的 application-internal 結果
 */
public record AgentLoopResult(
        AnalysisRunId runId,
        RunOutcome outcome,
        String responseText,
        Optional<AnswerDocument> answerDocument,
        RevisionVector finalRevisions) {

    public AgentLoopResult {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(outcome, "agent loop outcome must not be null");
        Objects.requireNonNull(responseText, "agent loop response text must not be null");
        Objects.requireNonNull(answerDocument, "agent loop answer document must not be null");
        Objects.requireNonNull(finalRevisions, "agent loop final revisions must not be null");
        if (responseText.isBlank()) {
            throw new IllegalArgumentException("agent loop response text must not be blank");
        }
        if (outcome == RunOutcome.COMPLETED && answerDocument.isEmpty()) {
            throw new IllegalArgumentException("completed agent loop result must contain an answer document");
        }
        if ((outcome == RunOutcome.FAILED || outcome == RunOutcome.CANCELLED) && answerDocument.isPresent()) {
            throw new IllegalArgumentException("failed or cancelled agent loop result must not contain an answer document");
        }
        if (answerDocument.isPresent()
                && !responseText.equals(answerDocument.orElseThrow().renderParagraphs())) {
            throw new IllegalArgumentException("agent loop response text must render the answer document exactly");
        }
    }
}
