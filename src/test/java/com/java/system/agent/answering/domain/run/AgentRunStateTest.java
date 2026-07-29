package com.java.system.agent.answering.domain.run;

import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * AgentRunState terminal runtime notice 不變量測試
 */
class AgentRunStateTest {

    @Test
    void initializes_without_a_runtime_notice() {
        AgentRunState state = AgentRunState.initial(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                budget(), identity());

        assertThat(state.runtimeNoticeReason()).isEmpty();
    }

    @Test
    void permits_runtime_notice_only_for_an_inconclusive_concluded_run() {
        RunAttempt attempt = RunAttempt.empty(new AnalysisAttemptId("attempt-1"));
        AgentRunState state = new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.CONCLUDED, attempt,
                1, budget(), 0, 0, 1, Optional.of(RunOutcome.INCONCLUSIVE),
                Optional.of(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED), Optional.empty(), Optional.empty(), Optional.empty(), identity());

        assertThat(state.runtimeNoticeReason()).contains(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED);
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRunState(new AnalysisRunId("run-1"),
                AgentRunStatus.CONCLUDED, attempt, 1, budget(), 0, 0, 1, Optional.of(RunOutcome.FAILED),
                Optional.of(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED), Optional.empty(), Optional.empty(), Optional.empty(), identity()));
    }

    @Test
    void permits_planning_tool_contract_failure_reason_only_for_a_failed_concluded_run() {
        RunAttempt attempt = RunAttempt.empty(new AnalysisAttemptId("attempt-1"));
        AgentRunState state = new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.CONCLUDED, attempt,
                1, budget(), 0, 0, 1, Optional.of(RunOutcome.FAILED),
                Optional.empty(), Optional.of(RunFailureReason.PLANNING_TOOL_CONTRACT), Optional.empty(), Optional.empty(), identity());

        assertThat(state.failureReason()).contains(RunFailureReason.PLANNING_TOOL_CONTRACT);
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRunState(new AnalysisRunId("run-1"),
                AgentRunStatus.CONCLUDED, attempt, 1, budget(), 0, 0, 1, Optional.of(RunOutcome.INCONCLUSIVE),
                Optional.empty(), Optional.of(RunFailureReason.PLANNING_TOOL_CONTRACT), Optional.empty(), Optional.empty(), identity()));
    }

    private static AttemptBudget budget() {
        return new AttemptBudget(2, 0, 2, 0, 2, 0, 1, 0);
    }

    private static RunRequestIdentity identity() {
        return new RunRequestIdentity("session-1", new ParticipantRef("test", "participant"), "question");
    }
}
