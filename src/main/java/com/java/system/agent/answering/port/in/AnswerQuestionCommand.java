package com.java.system.agent.answering.port.in;

import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;

import java.util.Objects;

/**
 * {@link AnswerQuestionUseCase} 的輸入：交由唯一驗證 action loop 處理的原始問題
 */
public record AnswerQuestionCommand(
        AnalysisRunId runId,
        SessionId sessionId,
        ParticipantRef participant,
        String question,
        AttemptBudget budget,
        AnswerExecutionMode executionMode,
        int executionAttempt) {

    public AnswerQuestionCommand(
            AnalysisRunId runId,
            SessionId sessionId,
            ParticipantRef participant,
            String question,
            AttemptBudget budget) {
        this(runId, sessionId, participant, question, budget, AnswerExecutionMode.INITIAL, 1);
    }

    public AnswerQuestionCommand {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(sessionId, "session ID must not be null");
        Objects.requireNonNull(participant, "participant must not be null");
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(budget, "analysis attempt budget must not be null");
        Objects.requireNonNull(executionMode, "answer execution mode must not be null");
        if (question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        executionMode.validateAttempt(executionAttempt);
    }
}
