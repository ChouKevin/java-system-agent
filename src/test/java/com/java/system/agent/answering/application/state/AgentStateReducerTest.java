package com.java.system.agent.answering.application.state;

import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.ExternalHttpMethod;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.run.RuntimeNoticeReason;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void consumes_execute_budget_without_consuming_query_budget() {
        AgentRunState state = runningState();

        AgentRunState reduced = reducer.reduce(state, new AgentEvent.ExecuteBudgetConsumed(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision())).candidateState();

        assertThat(reduced.budget().usedExecuteExecutions()).isEqualTo(1);
        assertThat(reduced.budget().usedQueryExecutions()).isZero();
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

    @Test
    void preserves_ordered_model_interactions_across_a_restarted_attempt() {
        AgentRunState state = runningState();
        QueryAction query = queryAction(state);

        state = reduce(state, new AgentEvent.ActionSelected(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), query));
        state = reduce(state, new AgentEvent.ActionAccepted(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), query));
        state = reduce(state, new AgentEvent.ActionResultRecorded(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                new ActionResult.QuerySucceeded(List.of("candidate-1"), List.of("evidence-1"),
                        List.of("observation-1"))));
        state = reduce(state, new AgentEvent.AttemptInvalidated(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), "restart", false));
        AnalysisAttemptId restartedAttemptId = new AnalysisAttemptId("attempt-2");
        state = reduce(state, new AgentEvent.AttemptStarted(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                RunAttempt.empty(restartedAttemptId)));
        ExecuteAction execute = new ExecuteAction(ExternalHttpMethod.POST, "https://example.test/orders",
                Optional.empty(), "Preview the requested mutation");
        state = reduce(state, new AgentEvent.ActionSelected(
                state.runId(), restartedAttemptId, state.stateRevision(), execute));

        assertThat(state.modelInteractions()).containsExactly(
                new ModelInteraction.ActionSelected(new AnalysisAttemptId("attempt-1"), query),
                new ModelInteraction.ActionResultRecorded(new AnalysisAttemptId("attempt-1"),
                        new ActionResult.QuerySucceeded(List.of("candidate-1"), List.of("evidence-1"),
                                List.of("observation-1"))),
                new ModelInteraction.ActionSelected(restartedAttemptId, execute));
        List<ModelInteraction> interactions = state.modelInteractions();
        assertThatThrownBy(() -> interactions.add(
                new ModelInteraction.ActionSelected(restartedAttemptId, execute)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejects_a_second_selection_in_one_attempt_but_allows_another_attempt_to_remain_unresolved() {
        AgentRunState state = runningState();
        QueryAction firstQuery = queryAction(state);
        state = reduce(state, new AgentEvent.ActionSelected(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), firstQuery));

        AgentRunState selected = state;
        assertThatThrownBy(() -> reduce(selected, new AgentEvent.ActionSelected(
                selected.runId(), selected.currentAttempt().attemptId(), selected.stateRevision(),
                queryAction(selected))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved");

        state = reduce(state, new AgentEvent.AttemptInvalidated(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), "recovery", false));
        AnalysisAttemptId restartedAttemptId = new AnalysisAttemptId("attempt-2");
        state = reduce(state, new AgentEvent.AttemptStarted(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                RunAttempt.empty(restartedAttemptId)));
        ExecuteAction execute = new ExecuteAction(ExternalHttpMethod.POST, "https://example.test/orders",
                Optional.empty(), "Preview the requested mutation");

        AgentRunState restarted = reduce(state, new AgentEvent.ActionSelected(
                state.runId(), restartedAttemptId, state.stateRevision(), execute));

        assertThat(restarted.modelInteractions()).containsExactly(
                new ModelInteraction.ActionSelected(new AnalysisAttemptId("attempt-1"), firstQuery),
                new ModelInteraction.ActionSelected(restartedAttemptId, execute));
    }

    @Test
    void rejects_an_action_result_that_does_not_match_its_unresolved_selection() {
        AgentRunState state = runningState();
        QueryAction query = queryAction(state);
        state = reduce(state, new AgentEvent.ActionSelected(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), query));

        AgentRunState selected = state;
        assertThatThrownBy(() -> reduce(selected, new AgentEvent.ActionResultRecorded(
                selected.runId(), selected.currentAttempt().attemptId(), selected.stateRevision(),
                new ActionResult.AnswerAccepted())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void requires_accepted_and_rejected_actions_to_match_the_unresolved_selection() {
        AgentRunState state = runningState();
        QueryAction selectedQuery = queryAction(state);
        state = reduce(state, new AgentEvent.ActionSelected(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), selectedQuery));
        ExecuteAction anotherAction = new ExecuteAction(ExternalHttpMethod.POST, "https://example.test/orders",
                Optional.empty(), "Preview the requested mutation");

        AgentRunState selected = state;
        assertThatThrownBy(() -> reduce(selected, new AgentEvent.ActionAccepted(
                selected.runId(), selected.currentAttempt().attemptId(), selected.stateRevision(), anotherAction)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved selected action");
        assertThatThrownBy(() -> reduce(selected, new AgentEvent.ActionRejected(
                selected.runId(), selected.currentAttempt().attemptId(), selected.stateRevision(),
                Optional.of(anotherAction), "INVALID_ACTION", "rejected")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved selected action");
    }

    @Test
    void atomically_records_a_question_plan_and_closes_its_selected_action() {
        AgentRunState state = runningState();
        QuestionPlan plan = plan();
        PlanAction action = new PlanAction(plan);
        AgentRunState selected = reduce(state, new AgentEvent.ActionSelected(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action));

        AgentRunState recorded = reduce(selected, new AgentEvent.QuestionPlanCreated(
                selected.runId(), selected.currentAttempt().attemptId(), selected.stateRevision(), plan));

        assertThat(recorded.questionPlan()).contains(plan);
        assertThat(recorded.budget().usedAgentSteps()).isEqualTo(selected.budget().usedAgentSteps() + 1);
        assertThat(recorded.budget().usedQueryExecutions()).isEqualTo(selected.budget().usedQueryExecutions());
        assertThat(recorded.acceptedActionCount()).isEqualTo(selected.acceptedActionCount() + 1);
        assertThat(recorded.unresolvedSelectedAction()).isEmpty();
        assertThat(recorded.modelInteractions()).last().isEqualTo(
                new ModelInteraction.ActionResultRecorded(recorded.currentAttempt().attemptId(),
                        new ActionResult.QuestionPlanRecorded(plan)));

        assertThatThrownBy(() -> reduce(recorded, new AgentEvent.QuestionPlanCreated(
                recorded.runId(), recorded.currentAttempt().attemptId(), recorded.stateRevision(), plan)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question plan");
        assertThat(recorded.questionPlan()).contains(plan);
        assertThat(recorded.stateRevision()).isEqualTo(selected.stateRevision() + 1);
    }

    private AgentRunState runningState() {
        AgentRunState initial = AgentRunState.initial(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                new AttemptBudget(3, 0, 2, 0, 1, 0, 2, 0, 1, 0),
                new RunRequestIdentity("session-1", new ParticipantRef("test", "participant"), "question"));
        AgentRunState started = reducer.reduce(initial, new AgentEvent.RunStarted(
                initial.runId(), initial.currentAttempt().attemptId(), initial.stateRevision())).candidateState();
        return reducer.reduce(started, new AgentEvent.AttemptStarted(
                started.runId(), started.currentAttempt().attemptId(), started.stateRevision(),
                started.currentAttempt())).candidateState();
    }

    private AgentRunState reduce(AgentRunState state, AgentEvent event) {
        return reducer.reduce(state, event).candidateState();
    }

    private QueryAction queryAction(AgentRunState state) {
        CapabilityHandle capability = new CapabilityHandle("capability-1", new HandleBinding(
                state.runId(), state.currentAttempt().attemptId(), RevisionVector.empty()));
        return new QueryAction(capability, List.of(), "Find the route", new CapabilityInputPayload("{}"),
                "Need the entry point");
    }

    private static QuestionPlan plan() {
        return new QuestionPlan(List.of(new InformationNeed(new InformationNeedId("need-1"), "Trace the route")));
    }
}
