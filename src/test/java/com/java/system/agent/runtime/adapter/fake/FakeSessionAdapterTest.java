package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeSessionAdapterTest {

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
                new ConversationTurn(runId, "different", "answer-1", ConversationTurnType.ANSWER)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.append(sessionId,
                new ConversationTurn(runId, "question-1 ", "answer-1", ConversationTurnType.ANSWER)))
                .isInstanceOf(IllegalStateException.class);
    }

    private ConversationTurn turn(AnalysisRunId runId, int index) {
        return new ConversationTurn(runId, "question-" + index, "answer-" + index,
                ConversationTurnType.ANSWER);
    }
}
