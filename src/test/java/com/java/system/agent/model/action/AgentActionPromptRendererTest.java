package com.java.system.agent.model.action;

import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentActionPromptRenderer 的 participant 歷史輸出測試
 */
class AgentActionPromptRendererTest {

    @Test
    void renders_each_history_turn_with_its_stable_participant_label() {
        SessionHistory history = new SessionHistory(List.of(
                new ConversationTurn(new AnalysisRunId("run-1"), new ParticipantRef("slack", "U123456"),
                        "請查詢付款流程", "付款流程如下", ConversationTurnType.ANSWER),
                new ConversationTurn(new AnalysisRunId("run-2"), new ParticipantRef("slack", "U789012"),
                        "也包含退款流程", "退款流程如下", ConversationTurnType.ANSWER)));
        AgentPromptContext context = new AgentPromptContext(
                "請查詢付款流程", history, new AnalysisRunId("run-3"), new AnalysisAttemptId("attempt-1"),
                Map.of(), Map.of(), Map.of(), Map.of(), Optional.empty(),
                new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0));

        String prompt = new AgentActionPromptRenderer().render(context);

        assertThat(prompt).contains("""
                Session turns:
                participant[slack:U123456]: 請查詢付款流程
                assistant: 付款流程如下
                participant[slack:U789012]: 也包含退款流程
                assistant: 退款流程如下
                """);
        assertThat(prompt).doesNotContain("- user:", "  type:");
    }
}
