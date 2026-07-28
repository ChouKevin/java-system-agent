package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.port.in.AnswerQuestionCommand;
import com.java.system.agent.runtime.port.in.AnswerQuestionResult;
import com.java.system.agent.runtime.port.in.AnswerQuestionUseCase;

import java.util.Objects;

/**
 * 將公開提問契約委派給唯一 Validated Agent Loop 的邊界服務
 */
public final class AnalysisApplicationService implements AnswerQuestionUseCase {

    private final ValidatedAgentLoop loop;

    public AnalysisApplicationService(ValidatedAgentLoop loop) {
        this.loop = Objects.requireNonNull(loop, "validated agent loop must not be null");
    }

    @Override
    public AnswerQuestionResult answer(AnswerQuestionCommand command) {
        Objects.requireNonNull(command, "answer question command must not be null");
        AgentLoopRequest request = new AgentLoopRequest(
                command.runId(), command.sessionId(), command.participant(), command.question(), command.budget(),
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
