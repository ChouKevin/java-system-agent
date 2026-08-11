package com.java.system.agent.answering.domain.run;

import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        assertThat(state.modelInteractions()).isEmpty();
    }

    @Test
    void permits_runtime_notice_only_for_an_inconclusive_concluded_run() {
        RunAttempt attempt = RunAttempt.empty(new AnalysisAttemptId("attempt-1"));
        AgentRunState state = new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.CONCLUDED, attempt,
                1, budget(), 0, 0, 1, Optional.of(RunOutcome.INCONCLUSIVE),
                Optional.of(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), identity(), List.of());

        assertThat(state.runtimeNoticeReason()).contains(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED);
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRunState(new AnalysisRunId("run-1"),
                AgentRunStatus.CONCLUDED, attempt, 1, budget(), 0, 0, 1, Optional.of(RunOutcome.FAILED),
                Optional.of(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), identity(), List.of()));
    }

    @Test
    void permits_planning_tool_contract_failure_reason_only_for_a_failed_concluded_run() {
        RunAttempt attempt = RunAttempt.empty(new AnalysisAttemptId("attempt-1"));
        AgentRunState state = new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.CONCLUDED, attempt,
                1, budget(), 0, 0, 1, Optional.of(RunOutcome.FAILED),
                Optional.empty(), Optional.of(RunFailureReason.PLANNING_TOOL_CONTRACT), Optional.empty(), Optional.empty(),
                Optional.empty(), identity(), List.of());

        assertThat(state.failureReason()).contains(RunFailureReason.PLANNING_TOOL_CONTRACT);
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRunState(new AnalysisRunId("run-1"),
                AgentRunStatus.CONCLUDED, attempt, 1, budget(), 0, 0, 1, Optional.of(RunOutcome.INCONCLUSIVE),
                Optional.empty(), Optional.of(RunFailureReason.PLANNING_TOOL_CONTRACT), Optional.empty(), Optional.empty(),
                Optional.empty(), identity(), List.of()));
    }

    @Test
    void requires_the_snapshot_question_plan_to_match_exactly_one_recorded_plan_result() {
        RunAttempt attempt = RunAttempt.empty(new AnalysisAttemptId("attempt-1"));
        QuestionPlan plan = plan("need-1");
        List<ModelInteraction> interactions = List.of(
                new ModelInteraction.ActionSelected(new AnalysisAttemptId("attempt-1"), new PlanAction(plan)),
                new ModelInteraction.ActionResultRecorded(
                        new AnalysisAttemptId("attempt-1"), new ActionResult.QuestionPlanRecorded(plan)));

        AgentRunState state = new AgentRunState(new AnalysisRunId("run-1"), AgentRunStatus.RUNNING, attempt,
                1, budget(), 1, 0, 1, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(plan), identity(), interactions);

        assertThat(state.questionPlan()).contains(plan);
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRunState(
                new AnalysisRunId("run-1"), AgentRunStatus.RUNNING, attempt, 1, budget(), 1, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), identity(), interactions));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRunState(
                new AnalysisRunId("run-1"), AgentRunStatus.RUNNING, attempt, 1, budget(), 1, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(plan("need-2")), identity(), interactions));
    }

    private static QuestionPlan plan(String needId) {
        return new QuestionPlan(List.of(new InformationNeed(new InformationNeedId(needId), "Trace the route")));
    }

    private static AttemptBudget budget() {
        return new AttemptBudget(2, 0, 2, 0, 1, 0, 2, 0, 1, 0);
    }

    private static RunRequestIdentity identity() {
        return new RunRequestIdentity("session-1", new ParticipantRef("test", "participant"), "question");
    }
}
