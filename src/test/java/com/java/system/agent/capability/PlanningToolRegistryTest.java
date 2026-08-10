package com.java.system.agent.capability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.capability.planning.AnswerPlanningToolRegistration;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.ClarifyPlanningToolRegistration;
import com.java.system.agent.capability.planning.ExecutePlanningToolRegistration;
import com.java.system.agent.capability.planning.FollowUpOnlyQueryRegistration;
import com.java.system.agent.capability.planning.PlanningToolCategory;
import com.java.system.agent.capability.planning.PlanningToolDescriptor;
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
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
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
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
    void exposesDescriptorsForEachPlanningToolRegistrationCategory() {
        CanonicalCapabilityPayloadCodec payloadCodec = payloadCodec();
        QueryPlanningToolRegistration<TestInput, TestInput> query = queryRegistration("query_tool", "v1", payloadCodec);
        FollowUpOnlyQueryRegistration<TestInput> followUp = PlanningToolRegistry.followUpOnlyRegistration(
                sourceSegmentPolicy(), TestInput.class,
                (executionContext, input) -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()));
        List<PlanningToolRegistration<?>> registrations = List.of(
                query,
                followUp,
                new AnswerPlanningToolRegistration<>("agent_submit_answer", SubmitAnswerPlanningInput.class,
                        new SubmitAnswerPlanningMapper()),
                new ClarifyPlanningToolRegistration<>("agent_request_clarification", RequestClarificationPlanningInput.class,
                        new RequestClarificationPlanningMapper()),
                new ExecutePlanningToolRegistration());

        assertThat(registrations)
                .allSatisfy(registration -> assertThat(registration.descriptor().toolName())
                        .isEqualTo(registration.name()));
        assertThat(query.descriptor()).isEqualTo(new PlanningToolDescriptor(
                PlanningToolCategory.QUERY, query.name(), Optional.of(query.policy()), Optional.empty()));
        assertThat(followUp.descriptor()).isEqualTo(new PlanningToolDescriptor(
                PlanningToolCategory.FOLLOW_UP_QUERY, followUp.name(), Optional.of(followUp.policy()), Optional.empty()));
        assertThat(registrations)
                .filteredOn(registration -> registration.descriptor().category() == PlanningToolCategory.ANSWER
                        || registration.descriptor().category() == PlanningToolCategory.CLARIFY
                        || registration.descriptor().category() == PlanningToolCategory.EXECUTE)
                .allSatisfy(registration -> {
                    PlanningToolDescriptor descriptor = registration.descriptor();
                    assertThat(descriptor.capability()).isEmpty();
                    assertThat(descriptor.guidanceId()).isEmpty();
                });
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

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_request_clarification; reason=JSON_CONTRACT"));
    }

    @Test
    void keepsFixedAnswerAndClarificationToolsIssuedAfterExecuteBudgetIsConsumed() {
        PlanningToolRegistry registry = registryWithExecutePreview();

        List<String> availableNames = registry.issuedRegistrations(context(0)).stream()
                .map(registration -> registration.name())
                .toList();
        List<String> exhaustedExecuteNames = registry.issuedRegistrations(context(1)).stream()
                .map(registration -> registration.name())
                .toList();

        assertThat(availableNames).contains("execute_http", "agent_submit_answer", "agent_request_clarification");
        assertThat(exhaustedExecuteNames)
                .contains("agent_submit_answer", "agent_request_clarification")
                .doesNotContain("execute_http");
    }

    @Test
    void issuesOnlyTheExactTargetToolForCurrentProviderBoundFollowUp() {
        PlanningToolRegistry registry = followUpRegistry(new AtomicInteger());

        assertThat(registry.issuedRegistrations(contextWithoutFollowUps()))
                .extracting(PlanningToolRegistration::name)
                .doesNotContain("codebase_follow_up", "codebase_get_source_segment");
        assertThat(registry.issuedRegistrations(contextWithFollowUp()))
                .extracting(PlanningToolRegistration::name)
                .contains("codebase_get_source_segment")
                .doesNotContain("codebase_follow_up");
        assertThat(registry.registrations())
                .filteredOn(registration -> registration.name().equals("codebase_get_source_segment"))
                .singleElement()
                .satisfies(registration -> assertThat(registration.description())
                        .contains("provider-bound follow-up for codebase_get_source_segment")
                        .contains("opaque FOLLOW_UP candidate handle"));
    }

    @Test
    void exposesFollowUpOnlyCapabilityByItsTargetNameWithTheBoundPayload() {
        AtomicInteger executorCalls = new AtomicInteger();
        PlanningToolRegistry registry = followUpRegistry(executorCalls);

        AgentActionProposal proposal = registry.interpretToolCall("codebase_get_source_segment", """
                {"followUpCandidateHandle":"candidate-follow-up",
                 "questionToResolve":"Read the continuation",
                 "rationale":"The provider issued this source continuation"}
                """, contextWithFollowUp());

        QueryAction action = (QueryAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.capability()).isEqualTo(sourceSegmentCapabilityHandle());
        assertThat(action.candidates()).containsExactly(new CandidateHandleRef("candidate-follow-up"));
        assertThat(action.payload()).isEqualTo(boundPayload());
        assertThat(executorCalls).hasValue(0);
    }

    @Test
    void rejectsFollowUpSelectorsForUnknownWrongKindAndMissingCapabilityBeforeExecution() {
        AtomicInteger executorCalls = new AtomicInteger();
        PlanningToolRegistry registry = followUpRegistry(executorCalls);

        AgentActionProposal unknown = registry.interpretToolCall(
                "codebase_get_source_segment", followUpInput("unknown-candidate"), contextWithFollowUp());
        AgentActionProposal repository = registry.interpretToolCall(
                "codebase_get_source_segment", followUpInput("repository-candidate"),
                contextWithFollowUpAndRepositoryCandidate());
        AgentActionProposal capabilityAbsent = registry.interpretToolCall("codebase_get_source_segment",
                followUpInput("candidate-follow-up"), contextWithFollowUpButNoTargetCapability());

        assertThat(unknown).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(repository).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(capabilityAbsent).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(executorCalls).hasValue(0);
    }

    @Test
    void rejectsFollowUpSelectorWhenCurrentContextContainsStaleBoundHandles() {
        AtomicInteger executorCalls = new AtomicInteger();
        PlanningToolRegistry registry = followUpRegistry(executorCalls);
        AgentPromptContext staleContext = contextWithStaleFollowUpBindings();

        assertThat(registry.issuedRegistrations(staleContext))
                .extracting(PlanningToolRegistration::name)
                .doesNotContain("codebase_follow_up", "codebase_get_source_segment");
        assertThat(registry.interpretToolCall(
                "codebase_get_source_segment", followUpInput("candidate-old-attempt"), staleContext))
                .isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(executorCalls).hasValue(0);
    }

    @Test
    void executesFollowUpOnlyRegistrationThroughTheCommonQueryExecutionIndex() {
        AtomicInteger executorCalls = new AtomicInteger();
        PlanningToolRegistry registry = followUpRegistry(executorCalls);
        CapabilityInvocation invocation = new CapabilityInvocation(sourceSegmentPolicy(), List.of(),
                "Read the continuation", boundPayload(), binding().revisionVector());

        CapabilityExecutionResult result = registry.execute(invocation);

        assertThat(result).isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(executorCalls).hasValue(1);
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

    private static PlanningToolRegistry registryWithExecutePreview() {
        CanonicalCapabilityPayloadCodec payloadCodec = payloadCodec();
        return registry(List.of(provider(List.of(
                queryRegistration("query_tool", "v1", payloadCodec),
                new AnswerPlanningToolRegistration<>("agent_submit_answer", SubmitAnswerPlanningInput.class,
                        new SubmitAnswerPlanningMapper()),
                new ClarifyPlanningToolRegistration<>("agent_request_clarification", RequestClarificationPlanningInput.class,
                        new RequestClarificationPlanningMapper()),
                new ExecutePlanningToolRegistration()))));
    }

    private static PlanningToolRegistry followUpRegistry(AtomicInteger executorCalls) {
        FollowUpOnlyQueryRegistration<TestInput> sourceSegment = PlanningToolRegistry.followUpOnlyRegistration(
                sourceSegmentPolicy(), TestInput.class,
                (executionContext, input) -> {
                    executorCalls.incrementAndGet();
                    return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
                });
        return registry(List.of(provider(List.of(sourceSegment))));
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
        return context(0);
    }

    private static AgentPromptContext context(int usedExecuteExecutions) {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        return new AgentPromptContext("Find routes", SessionHistory.empty(), runId, attemptId, Map.of(), Map.of(), Map.of(),
                Map.of(), List.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, usedExecuteExecutions, 3, 0, 1, 0));
    }

    private static AgentPromptContext contextWithoutFollowUps() {
        return followUpContext(Map.of(sourceSegmentCapabilityHandle(), sourceSegmentPolicy()), Map.of());
    }

    private static AgentPromptContext contextWithFollowUp() {
        CandidateHandle handle = followUpCandidateHandle();
        return followUpContext(Map.of(sourceSegmentCapabilityHandle(), sourceSegmentPolicy()), Map.of(handle,
                new IssuedCandidate(handle, followUpCandidate())));
    }

    private static AgentPromptContext contextWithFollowUpAndRepositoryCandidate() {
        CandidateHandle followUpHandle = followUpCandidateHandle();
        CandidateHandle handle = new CandidateHandle("repository-candidate", binding(), CandidateKind.REPOSITORY);
        return followUpContext(Map.of(sourceSegmentCapabilityHandle(), sourceSegmentPolicy()), Map.of(
                followUpHandle, new IssuedCandidate(followUpHandle, followUpCandidate()),
                handle, new IssuedCandidate(handle, new RepositoryCandidate(repositoryId(), "The repository root"))));
    }

    private static AgentPromptContext contextWithFollowUpButNoTargetCapability() {
        CandidateHandle handle = followUpCandidateHandle();
        return followUpContext(Map.of(), Map.of(handle, new IssuedCandidate(handle, followUpCandidate())));
    }

    private static AgentPromptContext contextWithStaleFollowUpBindings() {
        HandleBinding oldBinding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-0"),
                RevisionVector.empty().pin(repositoryId(), new RepositoryRevision("revision-1")));
        CandidateHandle oldHandle = new CandidateHandle("candidate-old-attempt", oldBinding, CandidateKind.FOLLOW_UP);
        CapabilityHandle oldCapability = new CapabilityHandle("capability-source-segment-old", oldBinding);
        return new AgentPromptContext("Find routes", SessionHistory.empty(), new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"), Map.of(oldCapability, sourceSegmentPolicy()), Map.of(oldHandle,
                new IssuedCandidate(oldHandle, followUpCandidate())), Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static AgentPromptContext followUpContext(
            Map<CapabilityHandle, CapabilityPolicy> capabilities,
            Map<CandidateHandle, IssuedCandidate> candidates) {
        return new AgentPromptContext("Find routes", SessionHistory.empty(), new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"), capabilities, candidates, Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static CapabilityPolicy sourceSegmentPolicy() {
        return new CapabilityPolicy("codebase_get_source_segment", "v1", Set.of(CandidateKind.FOLLOW_UP), 1, 1);
    }

    private static CapabilityHandle sourceSegmentCapabilityHandle() {
        return new CapabilityHandle("capability-source-segment", binding());
    }

    private static CandidateHandle followUpCandidateHandle() {
        return new CandidateHandle("candidate-follow-up", binding(), CandidateKind.FOLLOW_UP);
    }

    private static FollowUpCandidate followUpCandidate() {
        return new FollowUpCandidate(repositoryId(), new RepositoryRevision("revision-1"), "codebase_get_source_segment",
                "v1", boundPayload(), "Read the remaining source segment");
    }

    private static CapabilityInputPayload boundPayload() {
        return new CapabilityInputPayload("{\"questionToResolve\":\"Read the continuation\",\"rationale\":\"The previous result was truncated\"}");
    }

    private static HandleBinding binding() {
        RepositoryId repositoryId = repositoryId();
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, new RepositoryRevision("revision-1"));
        return new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
    }

    private static RepositoryId repositoryId() {
        return new RepositoryId("repository-1");
    }

    private static String followUpInput(String handle) {
        return """
                {"followUpCandidateHandle":"%s",
                 "questionToResolve":"Read the continuation",
                 "rationale":"The previous result was truncated"}
                """.formatted(handle);
    }

    private static CanonicalCapabilityPayloadCodec payloadCodec() {
        return new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator());
    }

    private record TestInput(
            @JsonProperty(required = true) String questionToResolve,
            @JsonProperty(required = true) String rationale) {
    }
}
