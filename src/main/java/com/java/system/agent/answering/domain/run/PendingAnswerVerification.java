package com.java.system.agent.answering.domain.run;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.scope.RevisionVector;

import java.util.Objects;

/**
 * 已持久化且尚未取得 verifier 結果的回答 checkpoint
 */
public record PendingAnswerVerification(AnalysisAttemptId attemptId, RevisionVector revisions,
                                        AnswerDocument document, AnswerVerificationMode verificationMode) {
    public PendingAnswerVerification {
        Objects.requireNonNull(attemptId, "pending answer verification attempt ID must not be null");
        Objects.requireNonNull(revisions, "pending answer verification revisions must not be null");
        Objects.requireNonNull(document, "pending answer verification document must not be null");
        Objects.requireNonNull(verificationMode, "pending answer verification mode must not be null");
    }
}
