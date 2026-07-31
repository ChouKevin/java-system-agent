package com.java.system.agent.capability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.capability.planning.AnswerPlanningToolRegistration;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.ClarifyPlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistration;
import com.java.system.agent.capability.planning.QueryPlanningToolRegistration;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.capability.planning.RequestClarificationPlanningInput;
import com.java.system.agent.capability.planning.RequestClarificationPlanningMapper;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.capability.planning.SubmitAnswerPlanningInput;
import com.java.system.agent.capability.planning.SubmitAnswerPlanningMapper;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PlanningToolRegistry 的固定 action tool 與 issued catalog 邊界測試
 */
class PlanningToolRegistryTest {

    @Test
    void sortsRegistrationsByNameWhenProvidersArriveInReverseOrder() {
        CanonicalCapabilityPayloadCodec payloadCodec = payloadCodec();
        PlanningToolProvider codeIntelligenceProvider = provider(List.of(
                queryRegistration("codebase_suggest_api_route", "v1", payloadCodec),
                queryRegistration("codebase_outgoing_call_graph", "v1", payloadCodec),
                queryRegistration("codebase_lookup_api_route", "v1", payloadCodec),
                queryRegistration("codebase_list_entry_points", "v1", payloadCodec),
                queryRegistration("codebase_incoming_call_graph", "v1", payloadCodec)));
        PlanningToolProvider coreProvider = provider(List.of(
                new AnswerPlanningToolRegistration<>("agent_submit_answer", SubmitAnswerPlanningInput.class,
                        new SubmitAnswerPlanningMapper()),
                new ClarifyPlanningToolRegistration<>("agent_request_clarification",
                        RequestClarificationPlanningInput.class, new RequestClarificationPlanningMapper())));

        PlanningToolRegistry registry = registry(List.of(codeIntelligenceProvider, coreProvider));

        assertThat(registry.registrations()).extracting(planningToolRegistration -> planningToolRegistration.name()).containsExactly(
                "agent_request_clarification",
                "agent_submit_answer",
                "codebase_incoming_call_graph",
                "codebase_list_entry_points",
                "codebase_lookup_api_route",
                "codebase_outgoing_call_graph",
                "codebase_suggest_api_route");
    }

    @Test
    void rejectsDuplicateQueryIdentityBeforeProviderFacingNameValidation() {
        CanonicalCapabilityPayloadCodec payloadCodec = payloadCodec();

        assertThatThrownBy(() -> registry(List.of(
                        provider(List.of(queryRegistration("query_tool", "v1", payloadCodec))),
                        provider(List.of(queryRegistration("query_tool", "v1", payloadCodec))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate QUERY identity");
    }

    @Test
    void rejectsDuplicateProviderFacingNameWhenQueryVersionsDiffer() {
        CanonicalCapabilityPayloadCodec payloadCodec = payloadCodec();

        assertThatThrownBy(() -> registry(List.of(
                        provider(List.of(queryRegistration("query_tool", "v1", payloadCodec))),
                        provider(List.of(queryRegistration("query_tool", "v2", payloadCodec))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate provider-facing name");
    }

    @Test
    void exposesFixedToolsOnEveryTurnAndRejectsUnknownOrUnissuedNamesAsMalformedResponses() {
        PlanningToolRegistry registry = registry();
        AgentPromptContext context = context();

        List<String> issuedNames = registry.issuedRegistrations(context).stream()
                .map(planningToolRegistration -> planningToolRegistration.name()).toList();
        AgentActionProposal unknown = registry.interpretToolCall("unknown_tool", "{}", context);
        AgentActionProposal unissued = registry.interpretToolCall("query_tool", "{}", context);

        assertThat(issuedNames).containsExactlyInAnyOrder("agent_submit_answer", "agent_request_clarification");
        assertThat(issuedNames).doesNotContain("execute_http");
        assertThat(unknown).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(unissued).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
    }

    @Test
    void mapsStrictFixedToolInputFailuresToInvalidToolInput() {
        PlanningToolRegistry registry = registry();

        AgentActionProposal proposal = registry.interpretToolCall("agent_request_clarification", """
                {"question":"Which repository?","candidateHandles":[],"reason":"Scope is ambiguous","unknown":"value"}
                """, context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
    }

    private static PlanningToolRegistry registry() {
        CanonicalCapabilityPayloadCodec payloadCodec = payloadCodec();
        return registry(List.of(provider(List.of(
                queryRegistration("query_tool", "v1", payloadCodec),
                new AnswerPlanningToolRegistration<>("agent_submit_answer", SubmitAnswerPlanningInput.class,
                        new SubmitAnswerPlanningMapper()),
                new ClarifyPlanningToolRegistration<>("agent_request_clarification", RequestClarificationPlanningInput.class,
                        new RequestClarificationPlanningMapper())))));
    }

    private static PlanningToolRegistry registry(
            List<PlanningToolProvider> providers) {
        CanonicalCapabilityPayloadCodec payloadCodec = payloadCodec();
        return new PlanningToolRegistry(providers,
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()),
                payloadCodec);
    }

    private static PlanningToolProvider provider(List<PlanningToolRegistration<?>> registrations) {
        return () -> registrations;
    }

    private static QueryPlanningToolRegistration<TestInput, TestInput> queryRegistration(
            String name, String version, CanonicalCapabilityPayloadCodec payloadCodec) {
        CapabilityPolicy policy = new CapabilityPolicy(name, version, Set.of(CandidateKind.REPOSITORY), 0, 1);
        QueryPlanningMapper<TestInput, TestInput> queryMapper = input ->
                new QueryPlanningSelection<>(List.of(), input.questionToResolve(), input.rationale(), input);
        return PlanningToolRegistry.registration(policy, TestInput.class, TestInput.class, queryMapper,
                (executionContext, input) -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()),
                payloadCodec);
    }

    private static AgentPromptContext context() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        return new AgentPromptContext("Find routes", SessionHistory.empty(), runId, attemptId, Map.of(), Map.of(), Map.of(),
                Map.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static CanonicalCapabilityPayloadCodec payloadCodec() {
        return new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator());
    }

    private record TestInput(
            @JsonProperty(required = true) String questionToResolve,
            @JsonProperty(required = true) String rationale) {
    }
}
