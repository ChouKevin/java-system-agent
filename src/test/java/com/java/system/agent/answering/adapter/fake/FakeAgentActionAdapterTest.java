package com.java.system.agent.answering.adapter.fake;

import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FakeAgentActionAdapterTest {

    @Test
    void should_return_scripted_proposals_in_insertion_order_and_retain_complete_contexts() {
        AgentActionProposal first = new AgentActionProposal.Malformed("first malformed proposal");
        AgentActionProposal second = new AgentActionProposal.Malformed("second malformed proposal");
        FakeAgentActionAdapter adapter = new FakeAgentActionAdapter(first, second);
        AgentPromptContext context = context();

        assertThat(adapter.nextAction(context)).isEqualTo(first);
        assertThat(adapter.nextAction(context)).isEqualTo(second);
        assertThat(adapter.contexts()).containsExactly(context, context);
    }

    private AgentPromptContext context() {
        return new AgentPromptContext("question", SessionHistory.empty(), new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"), Map.of(), Map.of(), Map.of(),
                Map.of(), Optional.empty(),
                new AttemptBudget(2, 0, 2, 0, 2, 0, 2, 0));
    }
}
