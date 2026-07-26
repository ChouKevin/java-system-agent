package com.java.system.agent.runtime.domain.conversation;

import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionHistoryTest {

    @Test
    void should_defensively_copy_turns_in_the_original_order() {
        ConversationTurn first = turn("run-1", "first");
        List<ConversationTurn> supplied = new ArrayList<>(List.of(first));

        SessionHistory sessionHistory = new SessionHistory(supplied);
        supplied.clear();

        assertThat(sessionHistory.turns()).containsExactly(first);
        assertThatThrownBy(() -> sessionHistory.turns().add(turn("run-2", "second")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_preserve_whitespace_in_accepted_turn_content() {
        ConversationTurn turn = new ConversationTurn(new AnalysisRunId("run-1"),
                "  user message  ", "  assistant message  ", ConversationTurnType.ANSWER);

        assertThat(turn.userMessage()).isEqualTo("  user message  ");
        assertThat(turn.assistantMessage()).isEqualTo("  assistant message  ");
    }

    private ConversationTurn turn(String runId, String message) {
        return new ConversationTurn(new AnalysisRunId(runId), message, message, ConversationTurnType.ANSWER);
    }
}
