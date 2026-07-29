package com.java.system.agent.answering.adapter.fake;

import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeSessionAdapterTest {

    private static final ParticipantRef PARTICIPANT = new ParticipantRef("test", "participant-1");

    @Test
    void should_retain_all_turns_in_insertion_order_without_allowing_caller_mutation() {
        FakeSessionAdapter adapter = new FakeSessionAdapter();
        SessionId sessionId = new SessionId("thread-1");

        for (int index = 1; index <= 12; index++) {
            adapter.append(sessionId, turn(new AnalysisRunId("run-" + index), index));
        }
        SessionHistory sessionHistory = adapter.read(sessionId);

        assertThat(sessionHistory.turns()).hasSize(12);
        assertThat(sessionHistory.turns().getFirst().userMessage()).isEqualTo("question-1");
        assertThat(sessionHistory.turns().getLast().assistantMessage()).isEqualTo("answer-12");
        assertThatThrownBy(() -> sessionHistory.turns().add(turn(new AnalysisRunId("run-13"), 13)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_make_identical_run_turn_idempotent_and_reject_conflicts() {
        FakeSessionAdapter adapter = new FakeSessionAdapter();
        SessionId sessionId = new SessionId("thread-1");
        AnalysisRunId runId = new AnalysisRunId("run-1");
        ConversationTurn accepted = turn(runId, 1);

        adapter.append(sessionId, accepted);
        adapter.append(sessionId, accepted);

        assertThat(adapter.read(sessionId).turns()).containsExactly(accepted);
        assertThatThrownBy(() -> adapter.append(sessionId,
                new ConversationTurn(runId, PARTICIPANT, "different", "answer-1", ConversationTurnType.ANSWER)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.append(sessionId,
                new ConversationTurn(runId, PARTICIPANT, "question-1 ", "answer-1", ConversationTurnType.ANSWER)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_preserve_stored_participants_in_history_order() {
        FakeSessionAdapter adapter = new FakeSessionAdapter();
        SessionId sessionId = new SessionId("thread-1");
        ParticipantRef alice = new ParticipantRef("slack", "U123456");
        ParticipantRef bob = new ParticipantRef("slack", "U789012");

        adapter.append(sessionId, new ConversationTurn(new AnalysisRunId("run-1"), alice,
                "請查詢付款流程", "付款流程如下", ConversationTurnType.ANSWER));
        adapter.append(sessionId, new ConversationTurn(new AnalysisRunId("run-2"), bob,
                "也包含退款流程", "退款流程如下", ConversationTurnType.ANSWER));

        assertThat(adapter.read(sessionId).turns())
                .extracting(conversationTurn -> conversationTurn.participant())
                .containsExactly(alice, bob);
    }

    private ConversationTurn turn(AnalysisRunId runId, int index) {
        return new ConversationTurn(runId, PARTICIPANT, "question-" + index, "answer-" + index,
                ConversationTurnType.ANSWER);
    }
}
