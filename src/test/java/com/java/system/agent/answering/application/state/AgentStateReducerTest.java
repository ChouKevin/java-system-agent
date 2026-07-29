package com.java.system.agent.answering.application.state;

import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.run.RuntimeNoticeReason;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 事件 reducer 的 runtime notice 與預算語意測試
 */
class AgentStateReducerTest {

    private final AgentStateReducer reducer = new AgentStateReducer();

    @Test
    void consumes_query_budget_without_any_final_response_reserve() {
        AgentRunState state = runningState();

        AgentRunState reduced = reducer.reduce(state, new AgentEvent.QueryBudgetConsumed(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision())).candidateState();

        assertThat(reduced.budget().usedQueryExecutions()).isEqualTo(1);
        assertThat(reduced.budget().maxQueryExecutions()).isEqualTo(2);
    }

    @Test
    void concludes_budget_exhaustion_as_inconclusive_runtime_notice() {
        AgentRunState state = runningState();

        AgentRunState reduced = reducer.reduce(state, new AgentEvent.RunConcluded(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), RunOutcome.INCONCLUSIVE,
                Optional.of(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED), Optional.empty())).candidateState();

        assertThat(reduced.status()).isEqualTo(AgentRunStatus.CONCLUDED);
        assertThat(reduced.finalOutcome()).contains(RunOutcome.INCONCLUSIVE);
        assertThat(reduced.runtimeNoticeReason()).contains(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED);
    }

    @Test
    void rejects_runtime_notice_for_non_inconclusive_conclusion() {
        AgentRunState state = runningState();

        assertThatThrownBy(() -> reducer.reduce(state, new AgentEvent.RunConcluded(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), RunOutcome.FAILED,
                Optional.of(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED), Optional.empty())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime notice");
    }

    @Test
    void persists_planning_tool_contract_failure_only_for_a_failed_conclusion() {
        AgentRunState state = runningState();

        AgentRunState reduced = reducer.reduce(state, new AgentEvent.RunConcluded(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), RunOutcome.FAILED,
                Optional.empty(), Optional.of(RunFailureReason.PLANNING_TOOL_CONTRACT))).candidateState();

        assertThat(reduced.failureReason()).contains(RunFailureReason.PLANNING_TOOL_CONTRACT);
    }

    private AgentRunState runningState() {
        AgentRunState initial = AgentRunState.initial(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                new AttemptBudget(3, 0, 2, 0, 2, 0, 1, 0),
                new RunRequestIdentity("session-1", new ParticipantRef("test", "participant"), "question"));
        AgentRunState started = reducer.reduce(initial, new AgentEvent.RunStarted(
                initial.runId(), initial.currentAttempt().attemptId(), initial.stateRevision())).candidateState();
        return reducer.reduce(started, new AgentEvent.AttemptStarted(
                started.runId(), started.currentAttempt().attemptId(), started.stateRevision(),
                started.currentAttempt())).candidateState();
    }
}
