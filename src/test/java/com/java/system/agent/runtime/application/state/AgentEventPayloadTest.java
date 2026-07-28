package com.java.system.agent.runtime.application.state;

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
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEventPayloadTest {

    private static final ParticipantRef PARTICIPANT = new ParticipantRef("test", "participant-1");

    @Test
    void should_preserve_nonblank_rejection_description_and_invalidation_reason_exactly() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        String reason = "  retry after revision drift  ";
        AgentEvent.ActionRejected rejected = new AgentEvent.ActionRejected(runId, attemptId, 0,
                Optional.empty(), reason, true);
        AgentEvent.AttemptInvalidated invalidated = new AgentEvent.AttemptInvalidated(
                runId, attemptId, 0, reason, true);

        assertThat(rejected.originalAction()).isEmpty();
        assertThat(rejected.description()).isEqualTo(reason);
        assertThat(rejected.finalResponseMode()).isTrue();
        assertThat(invalidated.reason()).isEqualTo(reason);
        assertThat(invalidated.consumeRevisionRestart()).isTrue();
    }

    @Test
    void should_reject_a_non_accepted_answer_verdict() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AnswerVerdict rejected = new AnswerVerdict(AnswerDisposition.REJECTED, List.of(), List.of(), List.of(),
                List.of("verification rejected the answer"));

        assertThatThrownBy(() -> new AgentEvent.AnswerAccepted(runId, attemptId, 0,
                document(), AnswerAcceptance.llm(rejected), new SessionId("session-1"),
                new ConversationTurn(runId, PARTICIPANT, "question", "answer", ConversationTurnType.ANSWER), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accepted");
    }

    private AnswerDocument document() {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "answer", Optional.empty(), Set.of(), Set.of())));
    }
}
