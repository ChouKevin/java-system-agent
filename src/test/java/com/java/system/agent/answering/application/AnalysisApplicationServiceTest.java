package com.java.system.agent.answering.application;

import com.java.system.agent.answering.application.loop.AgentLoopRequest;
import com.java.system.agent.answering.application.loop.AgentLoopResult;
import com.java.system.agent.answering.application.loop.ValidatedAgentLoop;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.in.AnswerQuestionCommand;
import com.java.system.agent.answering.port.in.AnswerQuestionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisApplicationServiceTest {

    @Test
    void mapsThePublicCommandToTheSingleValidatedLoopAndMapsItsResult() {
        AgentLoopResult loopResult = new AgentLoopResult(
                new AnalysisRunId("run-1"),
                RunOutcome.COMPLETED,
                "Verified answer",
                Optional.of(document("Verified answer")),
                RunResponseKind.ANSWER,
                Optional.of(AnswerVerificationBasis.LLM),
                RevisionVector.empty());
        ValidatedAgentLoop loop = mock(ValidatedAgentLoop.class);
        AnalysisApplicationService service = new AnalysisApplicationService(loop, new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1"));
        AnswerQuestionCommand command = new AnswerQuestionCommand(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                participant(),
                "  How does it work?  ",
                new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0));

        AgentLoopRequest expectedRequest = new AgentLoopRequest(
                command.runId(), command.sessionId(), command.participant(), command.question(), new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1"), command.budget(),
                command.executionMode(), command.executionAttempt());
        when(loop.execute(expectedRequest)).thenReturn(loopResult);

        AnswerQuestionResult result = service.answer(command);

        verify(loop).execute(expectedRequest);
        assertThat(result).isEqualTo(new AnswerQuestionResult(
                loopResult.runId(),
                loopResult.outcome(),
                loopResult.responseText(),
                loopResult.answerDocument(),
                loopResult.responseKind(),
                loopResult.verificationBasis(),
                loopResult.finalRevisions()));
    }

    @Test
    void rejectsALoopResultForAnotherRun() {
        ValidatedAgentLoop loop = mock(ValidatedAgentLoop.class);
        AnalysisApplicationService service = new AnalysisApplicationService(loop, new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1"));
        AnswerQuestionCommand command = new AnswerQuestionCommand(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                participant(),
                "How does it work?",
                new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0));
        AgentLoopRequest expectedRequest = new AgentLoopRequest(
                command.runId(), command.sessionId(), command.participant(), command.question(), new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1"), command.budget(),
                command.executionMode(), command.executionAttempt());
        AgentLoopResult mismatchedResult = new AgentLoopResult(
                new AnalysisRunId("run-2"),
                RunOutcome.COMPLETED,
                "Verified answer",
                Optional.of(document("Verified answer")),
                RunResponseKind.ANSWER,
                Optional.of(AnswerVerificationBasis.LLM),
                RevisionVector.empty());
        when(loop.execute(expectedRequest)).thenReturn(mismatchedResult);

        assertThatIllegalStateException().isThrownBy(() -> service.answer(command))
                .withMessageContaining("run ID");

        verify(loop).execute(expectedRequest);
    }

    private AnswerDocument document(String text) {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, text, Optional.empty(), Set.of(), Set.of())));
    }

    private ParticipantRef participant() {
        return new ParticipantRef("test", "participant-1");
    }
}
