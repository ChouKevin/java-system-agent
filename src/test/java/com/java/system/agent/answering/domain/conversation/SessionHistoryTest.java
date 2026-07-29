package com.java.system.agent.answering.domain.conversation;

import com.java.system.agent.answering.domain.run.AnalysisRunId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionHistoryTest {

    private static final ParticipantRef ALICE = new ParticipantRef("slack", "U123456");
    private static final ParticipantRef BOB = new ParticipantRef("slack", "U789012");

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
                ALICE, "  user message  ", "  assistant message  ", ConversationTurnType.ANSWER);

        assertThat(turn.userMessage()).isEqualTo("  user message  ");
        assertThat(turn.assistantMessage()).isEqualTo("  assistant message  ");
    }

    @Test
    void should_preserve_participants_for_each_turn_in_order() {
        ConversationTurn first = new ConversationTurn(new AnalysisRunId("run-1"), ALICE,
                "請查詢付款流程", "付款流程如下", ConversationTurnType.ANSWER);
        ConversationTurn second = new ConversationTurn(new AnalysisRunId("run-2"), BOB,
                "也包含退款流程", "退款流程如下", ConversationTurnType.ANSWER);

        SessionHistory sessionHistory = new SessionHistory(List.of(first, second));

        assertThat(sessionHistory.turns())
                .extracting(ConversationTurn::participant)
                .containsExactly(ALICE, BOB);
    }

    @Test
    void should_normalize_participant_identity_and_render_its_prompt_label() {
        ParticipantRef participant = new ParticipantRef(" slack ", " U123456 ");

        assertThat(participant.sourceType()).isEqualTo("slack");
        assertThat(participant.participantKey()).isEqualTo("U123456");
        assertThat(participant.promptLabel()).isEqualTo("participant[slack:U123456]");
    }

    private ConversationTurn turn(String runId, String message) {
        return new ConversationTurn(new AnalysisRunId(runId), ALICE, message, message, ConversationTurnType.ANSWER);
    }
}
