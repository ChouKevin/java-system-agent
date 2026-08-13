package com.java.system.agent.capability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.CorePlanningToolProvider;
import com.java.system.agent.capability.planning.IssuedPlanningTool;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.capability.planning.QueryPlanningToolRegistration;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PlanningToolRegistryTest {

    @Test
    void normalizes_and_rejects_blank_capability_identity_components() {
        assertThat(new CapabilityPolicy(" query_tool ", " v1 "))
                .isEqualTo(new CapabilityPolicy("query_tool", "v1"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CapabilityPolicy(" ", "v1"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CapabilityPolicy("query_tool", " "));
    }

    @Test
    void issues_a_standard_query_for_the_current_capability_and_pinned_scope_without_candidates() {
        PlanningToolRegistry registry = registry();
        AgentPromptContext context = plannedContext(RevisionVector.empty().pin(new RepositoryId("repository-a"),
                new RepositoryRevision("revision-a")));

        assertThat(registry.issuedTools(context)).containsExactlyInAnyOrder(
                new IssuedPlanningTool("agent_submit_answer"),
                new IssuedPlanningTool("agent_request_clarification"),
                new IssuedPlanningTool("query_tool"));

        AgentActionProposal proposal = registry.interpretToolCall("query_tool", """
                {"questionToResolve":"Find callers","rationale":"The typed target is explicit"}
                """, context);

        QueryAction action = (QueryAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.capability().value()).isEqualTo("capability-query");
        assertThat(payloadCodec().decode(action.payload(), QueryInput.class))
                .isEqualTo(new QueryInput("Find callers", "The typed target is explicit"));
    }

    @Test
    void requires_a_pinned_scope_before_issuing_a_query() {
        PlanningToolRegistry registry = registry();

        assertThat(registry.issuedTools(plannedContext(RevisionVector.empty())))
                .doesNotContain(new IssuedPlanningTool("query_tool"));
    }

    @Test
    void issues_only_question_planning_before_a_durable_plan() {
        PlanningToolRegistry registry = registry();

        assertThat(registry.issuedRegistrations(planningContext()))
                .extracting(PlanningToolRegistration::name)
                .containsExactly("agent_plan_question");
        assertThat(registry.interpretToolCall("query_tool", "{}", planningContext()))
                .isEqualTo(new AgentActionProposal.Malformed(
                        "MALFORMED_ACTION_RESPONSE: requestedTool=query_tool; toolStatus=NOT_CURRENTLY_ISSUED; "
                                + "expected=currentlyIssuedTool"));
    }

    @Test
    void reserves_the_final_agent_step_for_answer_or_clarification() {
        PlanningToolRegistry registry = registry();
        RevisionVector revisions = RevisionVector.empty().pin(new RepositoryId("repository-a"),
                new RepositoryRevision("revision-a"));
        AttemptBudget finalStepBudget = new AttemptBudget(10, 9, 10, 0, 10, 0, 10, 0, 10, 0);

        assertThat(registry.issuedTools(plannedContext(revisions, finalStepBudget)))
                .containsExactlyInAnyOrder(
                        new IssuedPlanningTool("agent_submit_answer"),
                        new IssuedPlanningTool("agent_request_clarification"));
    }

    @Test
    void preserves_clarification_candidate_selection() {
        PlanningToolRegistry registry = registry();
        AgentActionProposal proposal = registry.interpretToolCall("agent_request_clarification", """
                {"question":"Which repository?","candidateHandles":[],"reason":"Scope is ambiguous"}
        """, plannedContext(RevisionVector.empty().pin(new RepositoryId("repository-a"),
                        new RepositoryRevision("revision-a"))));

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
    }

    @Test
    void rejects_strict_fixed_tool_input_without_exposing_untrusted_content() {
        PlanningToolRegistry registry = registry();

        AgentActionProposal proposal = registry.interpretToolCall("agent_request_clarification", """
                {"question":"Which repository?","candidateHandles":[],"reason":"Scope is ambiguous","unknown":"value"}
                """, plannedContext(RevisionVector.empty().pin(new RepositoryId("repository-a"),
                        new RepositoryRevision("revision-a"))));

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_request_clarification; reason=JSON_CONTRACT"));
    }

    private static PlanningToolRegistry registry() {
        CanonicalCapabilityPayloadCodec payloadCodec = payloadCodec();
        CapabilityPolicy policy = new CapabilityPolicy("query_tool", "v1");
        QueryPlanningMapper<QueryInput, QueryInput> mapper = input -> new QueryPlanningSelection<>(
                input.questionToResolve(), input.rationale(), input);
        QueryPlanningToolRegistration<QueryInput, QueryInput> query = PlanningToolRegistry.registration(
                policy, QueryInput.class, QueryInput.class, mapper,
                (context, input) -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()), payloadCodec);
        PlanningToolProvider provider = () -> List.of(query);
        return new PlanningToolRegistry(List.of(new CorePlanningToolProvider(), provider),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
    }

    private static AgentPromptContext planningContext() {
        return context(RevisionVector.empty(), List.of());
    }

    private static AgentPromptContext plannedContext(RevisionVector revisions) {
        return plannedContext(revisions, new AttemptBudget(10, 0, 10, 0, 10, 0, 10, 0, 10, 0));
    }

    private static AgentPromptContext plannedContext(RevisionVector revisions, AttemptBudget budget) {
        return context(revisions, List.of(new com.java.system.agent.answering.domain.run.ModelInteraction.ActionResultRecorded(
                new AnalysisAttemptId("attempt-1"), new com.java.system.agent.answering.domain.run.ActionResult.QuestionPlanRecorded(
                new com.java.system.agent.answering.domain.plan.QuestionPlan(List.of(new com.java.system.agent.answering.domain.plan.InformationNeed(
                        new com.java.system.agent.answering.domain.plan.InformationNeedId("need-1"), "Need")))))), budget);
    }

    private static AgentPromptContext context(
            RevisionVector revisions,
            List<com.java.system.agent.answering.domain.run.ModelInteraction> interactions) {
        return context(revisions, interactions, new AttemptBudget(10, 0, 10, 0, 10, 0, 10, 0, 10, 0));
    }

    private static AgentPromptContext context(
            RevisionVector revisions,
            List<com.java.system.agent.answering.domain.run.ModelInteraction> interactions,
            AttemptBudget budget) {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        CapabilityPolicy policy = new CapabilityPolicy("query_tool", "v1");
        CapabilityHandle handle = new CapabilityHandle("capability-query", new HandleBinding(runId, attemptId, revisions));
        return new AgentPromptContext("Question", SessionHistory.empty(), runId, attemptId,
                Map.of(handle, policy), Map.of(), Map.of(), Map.of(), interactions, Optional.empty(),
                budget);
    }

    private static CanonicalCapabilityPayloadCodec payloadCodec() {
        return new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator());
    }

    private record QueryInput(
            @JsonProperty(required = true) String questionToResolve,
            @JsonProperty(required = true) String rationale) {
    }
}
