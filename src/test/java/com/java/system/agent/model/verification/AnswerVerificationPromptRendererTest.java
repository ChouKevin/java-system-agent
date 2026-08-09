package com.java.system.agent.model.verification;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AnswerVerificationPromptRenderer 的 participant 歷史輸出測試
 */
class AnswerVerificationPromptRendererTest {

    @Test
    void requires_complete_answers_to_cover_every_explicit_part_of_the_question() {
        assertThat(AnswerVerificationPromptRenderer.SYSTEM_INSTRUCTION)
                .contains("every explicit part of the current question")
                .contains("ACCEPTED_COMPLETE only when every requested part is answered")
                .contains("ACCEPTED_INCONCLUSIVE only when the document explicitly states unavoidable missing information")
                .contains("REJECTED when a requested part is omitted")
                .contains("unaddressedParts", "rejectionReasons");
    }

    @Test
    void renders_each_history_turn_with_its_stable_participant_label() {
        SessionHistory history = new SessionHistory(List.of(
                new ConversationTurn(new AnalysisRunId("run-1"), new ParticipantRef("slack", "U123456"),
                        "請查詢付款流程", "付款流程如下", ConversationTurnType.ANSWER),
                new ConversationTurn(new AnalysisRunId("run-2"), new ParticipantRef("slack", "U789012"),
                        "也包含退款流程", "退款流程如下", ConversationTurnType.ANSWER)));
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "付款流程", Optional.empty(), Set.of(), Set.of())));
        AnswerVerificationContext context = new AnswerVerificationContext(
                "請查詢付款流程", history, document, List.of(), List.of(), List.of(), List.of());

        String prompt = new AnswerVerificationPromptRenderer().render(context, "response contract");

        assertThat(prompt).contains("""
                Session history:
                participant[slack:U123456]: 請查詢付款流程
                assistant: 付款流程如下
                participant[slack:U789012]: 也包含退款流程
                assistant: 退款流程如下
                """);
        assertThat(prompt).doesNotContain("- user:");
    }
}
