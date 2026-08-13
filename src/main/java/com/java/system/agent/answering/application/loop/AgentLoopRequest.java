package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.port.in.AnswerExecutionMode;

import java.util.Objects;

/**
 * Validated Agent Loop 的 application-internal 請求
 */
public record AgentLoopRequest(
        AnalysisRunId runId,
        SessionId sessionId,
        ParticipantRef participant,
        String question,
        RepositoryId repositoryId,
        AttemptBudget budget,
        AnswerExecutionMode executionMode,
        int executionAttempt) {

    public AgentLoopRequest(
            AnalysisRunId runId,
            SessionId sessionId,
            ParticipantRef participant,
            String question,
            RepositoryId repositoryId,
            AttemptBudget budget) {
        this(runId, sessionId, participant, question, repositoryId, budget, AnswerExecutionMode.INITIAL, 1);
    }

    public AgentLoopRequest {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(sessionId, "session ID must not be null");
        Objects.requireNonNull(participant, "agent loop participant must not be null");
        Objects.requireNonNull(question, "agent loop question must not be null");
        Objects.requireNonNull(repositoryId, "agent loop repository ID must not be null");
        Objects.requireNonNull(budget, "agent loop budget must not be null");
        Objects.requireNonNull(executionMode, "agent loop execution mode must not be null");
        if (question.isBlank()) {
            throw new IllegalArgumentException("agent loop question must not be blank");
        }
        executionMode.validateAttempt(executionAttempt);
    }
}
