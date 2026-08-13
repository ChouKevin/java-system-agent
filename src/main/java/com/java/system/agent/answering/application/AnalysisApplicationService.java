package com.java.system.agent.answering.application;

import com.java.system.agent.AgentRepositoryScopeProperties;
import com.java.system.agent.answering.application.loop.AgentLoopRequest;
import com.java.system.agent.answering.application.loop.AgentLoopResult;
import com.java.system.agent.answering.application.loop.ValidatedAgentLoop;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.port.in.AnswerQuestionCommand;
import com.java.system.agent.answering.port.in.AnswerQuestionResult;
import com.java.system.agent.answering.port.in.AnswerQuestionUseCase;

import java.util.Objects;

/**
 * 將公開提問契約委派給唯一 Validated Agent Loop 的邊界服務
 */
public final class AnalysisApplicationService implements AnswerQuestionUseCase {

    private final ValidatedAgentLoop loop;
    private final RepositoryId repositoryId;

    public AnalysisApplicationService(
            ValidatedAgentLoop loop,
            AgentRepositoryScopeProperties repositoryScopeProperties) {
        this.loop = Objects.requireNonNull(loop, "validated agent loop must not be null");
        AgentRepositoryScopeProperties scopeProperties = Objects.requireNonNull(
                repositoryScopeProperties, "repository scope properties must not be null");
        this.repositoryId = new RepositoryId(scopeProperties.repositoryId());
    }

    @Override
    public AnswerQuestionResult answer(AnswerQuestionCommand command) {
        Objects.requireNonNull(command, "answer question command must not be null");
        AgentLoopRequest request = new AgentLoopRequest(
                command.runId(), command.sessionId(), command.participant(), command.question(), repositoryId, command.budget(),
                command.executionMode(), command.executionAttempt());
        AgentLoopResult result = Objects.requireNonNull(
                loop.execute(request), "validated agent loop must return a result");
        if (!command.runId().equals(result.runId())) {
            throw new IllegalStateException("validated agent loop result run ID must match answer command run ID");
        }
        return new AnswerQuestionResult(
                result.runId(),
                result.outcome(),
                result.responseText(),
                result.answerDocument(),
                result.responseKind(),
                result.verificationBasis(),
                result.finalRevisions());
    }
}
