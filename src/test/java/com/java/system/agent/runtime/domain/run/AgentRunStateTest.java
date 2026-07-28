package com.java.system.agent.runtime.domain.run;

import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerAcceptance;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunStateTest {

    private static final ParticipantRef PARTICIPANT = new ParticipantRef("test", "participant-1");

    @Test
    void should_bootstrap_starting_run_with_first_empty_attempt() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AttemptBudget budget = new AttemptBudget(2, 0, 2, 0, 2, 0, 2, 0, 1, 0);

        AgentRunState state = AgentRunState.initial(runId, attemptId, budget, requestIdentity());

        assertThat(state.status()).isEqualTo(AgentRunStatus.STARTING);
        assertThat(state.currentAttempt().attemptId()).isEqualTo(attemptId);
        assertThat(state.attemptSequence()).isEqualTo(1);
        assertThat(state.stateRevision()).isZero();
        assertThat(state.finalOutcome()).isEmpty();
    }

    @Test
    void should_reject_pending_terminal_responses_outside_running_state() {
        RunAttempt attempt = RunAttempt.empty(new AnalysisAttemptId("attempt-1"));
        AttemptBudget budget = budget();
        PendingTerminalResponse pending = new PendingTerminalResponse.Answer(
                new SessionId("session-1"),
                new ConversationTurn(new AnalysisRunId("run-1"), PARTICIPANT, "question", "answer", ConversationTurnType.ANSWER),
                document(), AnswerAcceptance.llm(acceptedCompleteVerdict()));

        assertThatThrownBy(() -> new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.CONCLUDED, attempt,
                1, budget, 0, 0, 1, Optional.of(RunOutcome.FAILED), Optional.of(pending), Optional.empty(), requestIdentity()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending");
        assertThatThrownBy(() -> new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.STARTING, attempt,
                1, budget, 0, 0, 0, Optional.empty(), Optional.of(pending), Optional.empty(), requestIdentity()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending");
        assertThatThrownBy(() -> new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.RESTARTING, attempt,
                1, budget, 0, 0, 1, Optional.empty(), Optional.of(pending), Optional.empty(), requestIdentity()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending");
    }

    @Test
    void should_require_a_bootstrap_coherent_starting_state_while_allowing_running_pending_response() {
        RunAttempt attempt = RunAttempt.empty(new AnalysisAttemptId("attempt-1"));
        AttemptBudget budget = budget();
        PendingTerminalResponse pending = new PendingTerminalResponse.Clarification(
                new SessionId("session-1"),
                new ConversationTurn(new AnalysisRunId("run-1"), PARTICIPANT, "question", "which repository",
                        ConversationTurnType.CLARIFICATION),
                new ClarifyAction("which repository", List.of(), "scope is ambiguous"));

        assertThatThrownBy(() -> new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.STARTING, attempt,
                1, budget, 1, 0, 0, Optional.empty(), Optional.empty(), Optional.empty(), requestIdentity()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootstrap");
        assertThatThrownBy(() -> new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.STARTING, attempt,
                1, budget, 0, 0, 1, Optional.empty(), Optional.empty(), Optional.empty(), requestIdentity()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootstrap");

        AgentRunState running = new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.RUNNING, attempt,
                1, budget, 0, 0, 1, Optional.empty(), Optional.of(pending), Optional.empty(), requestIdentity());

        assertThat(running.pendingTerminalResponse()).contains(pending);
    }

    @Test
    void should_require_pending_terminal_content_to_match_the_persisted_request_identity() {
        RunAttempt attempt = RunAttempt.empty(new AnalysisAttemptId("attempt-1"));
        PendingTerminalResponse.Answer wrongRun = pendingAnswer(
                new SessionId("session-1"), new AnalysisRunId("other-run"), "question");
        PendingTerminalResponse.Answer wrongSession = pendingAnswer(
                new SessionId("session-2"), new AnalysisRunId("run-1"), "question");
        PendingTerminalResponse.Answer wrongQuestion = pendingAnswer(
                new SessionId("session-1"), new AnalysisRunId("run-1"), "other question");

        assertThatThrownBy(() -> runningState(attempt, wrongRun))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request identity");
        assertThatThrownBy(() -> runningState(attempt, wrongSession))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request identity");
        assertThatThrownBy(() -> runningState(attempt, wrongQuestion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request identity");
    }

    @Test
    void should_allow_valid_pending_content_to_be_retained_by_a_concluded_state() {
        RunAttempt attempt = RunAttempt.empty(new AnalysisAttemptId("attempt-1"));
        PendingTerminalResponse pending = new PendingTerminalResponse.Clarification(
                new SessionId("session-1"),
                new ConversationTurn(new AnalysisRunId("run-1"), PARTICIPANT, "question", "which repository",
                        ConversationTurnType.CLARIFICATION),
                new ClarifyAction("which repository", List.of(), "scope is ambiguous"));

        AgentRunState concluded = new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.CONCLUDED, attempt,
                1, budget(), 1, 0, 3, Optional.of(RunOutcome.INCONCLUSIVE), Optional.of(pending), Optional.empty(), requestIdentity());

        assertThat(concluded.pendingTerminalResponse()).contains(pending);
    }

    private AgentRunState runningState(RunAttempt attempt, PendingTerminalResponse pending) {
        return new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.RUNNING, attempt,
                1, budget(), 1, 0, 3, Optional.empty(), Optional.of(pending), Optional.empty(), requestIdentity());
    }

    private PendingTerminalResponse.Answer pendingAnswer(
            SessionId sessionId,
            AnalysisRunId runId,
            String question) {
        return new PendingTerminalResponse.Answer(
                sessionId,
                new ConversationTurn(runId, PARTICIPANT, question, document().renderParagraphs(), ConversationTurnType.ANSWER),
                document(),
                AnswerAcceptance.llm(acceptedCompleteVerdict()));
    }

    private AttemptBudget budget() {
        return new AttemptBudget(2, 0, 2, 0, 2, 0, 2, 0, 1, 0);
    }

    private RunRequestIdentity requestIdentity() {
        return new RunRequestIdentity("session-1", PARTICIPANT, "question");
    }

    private AnswerVerdict acceptedCompleteVerdict() {
        return new AnswerVerdict(AnswerDisposition.ACCEPTED_COMPLETE, List.of(), List.of(), List.of(), List.of());
    }

    private AnswerDocument document() {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "answer", Optional.empty(), Set.of(), Set.of())));
    }
}
