package com.java.system.agent.capability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.capability.planning.AnswerPlanningToolRegistration;
import com.java.system.agent.capability.planning.CandidateBoundExecutionPlanner;
import com.java.system.agent.capability.planning.CandidateBoundPlanningInput;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.ClarifyPlanningToolRegistration;
import com.java.system.agent.capability.planning.CorePlanningToolProvider;
import com.java.system.agent.capability.planning.ExecutePlanningToolRegistration;
import com.java.system.agent.capability.planning.FollowUpOnlyQueryRegistration;
import com.java.system.agent.capability.planning.IssuedPlanningTool;
import com.java.system.agent.capability.planning.PlanningToolCategory;
import com.java.system.agent.capability.planning.PlanningToolDescriptor;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanPlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanQuestionPlanningInput;
import com.java.system.agent.capability.planning.PlanQuestionPlanningMapper;
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
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
    void issuesOnlyQuestionPlanningBeforeTheDurablePlanAndRejectsToolsOutsideTheSnapshot() {
        PlanningToolRegistry registry = registry();
        AgentPromptContext withoutPlan = planningContext();
        AgentPromptContext withPlan = context();

        AgentActionProposal forcedQuery = registry.interpretToolCall("query_tool", "{}", withoutPlan);
        AgentActionProposal forcedPlan = registry.interpretToolCall("agent_plan_question", """
                {"needs":[{"id":"scope","description":"確認業務範圍"}]}
                """, withPlan);

        assertThat(registry.issuedRegistrations(withoutPlan))
                .extracting(PlanningToolRegistration::name)
                .containsExactly("agent_plan_question");
        assertThat(registry.issuedRegistrations(withPlan))
                .extracting(PlanningToolRegistration::name)
                .doesNotContain("agent_plan_question")
                .contains("agent_submit_answer", "agent_request_clarification");
        assertThat(forcedQuery).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: requestedTool=query_tool; toolStatus=NOT_CURRENTLY_ISSUED; "
                        + "expected=currentlyIssuedTool"));
        assertThat(forcedPlan).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: requestedTool=agent_plan_question; toolStatus=NOT_CURRENTLY_ISSUED; "
                        + "expected=currentlyIssuedTool"));
    }

    @Test
    void rejectsANoncanonicalQuestionPlanningRegistrationBeforeItCanBeIssued() {
        assertThatThrownBy(() -> new PlanPlanningToolRegistration<>("agent_plan_question_copy",
                PlanQuestionPlanningInput.class, new PlanQuestionPlanningMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PlanPlanningToolRegistration.NAME);
    }

    @Test
    void preservesSubmittedQuestionPlanNeedOrderInTheProposedPlanAction() {
        PlanningToolRegistry registry = registry();

        AgentActionProposal proposal = registry.interpretToolCall("agent_plan_question", """
                {"needs":[{"id":"scope","description":"確認業務範圍"},{"id":"boundary","description":"確認邊界條件"}]}
                """, planningContext());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Proposed(new PlanAction(new QuestionPlan(List.of(
                new InformationNeed(new InformationNeedId("scope"), "確認業務範圍"),
                new InformationNeed(new InformationNeedId("boundary"), "確認邊界條件"))))));
    }

    @Test
    void mapsDuplicateQuestionPlanNeedIdentifiersToTheSafePlanContractDiagnostic() {
        PlanningToolRegistry registry = registry();

        AgentActionProposal proposal = registry.interpretToolCall("agent_plan_question", """
                {"needs":[{"id":"scope","description":"確認業務範圍"},{"id":"scope","description":"重複的需求"}]}
                """, planningContext());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_plan_question; reason=QUESTION_PLAN_CONTRACT"));
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
                .satisfies(registration -> assertThat(registration.descriptor().guidanceId()).isEmpty());
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

        String invalidFollowUpSelection = "INVALID_TOOL_INPUT: tool=codebase_get_source_segment; "
                + "reason=FOLLOW_UP_SELECTION; invalidFields=[followUpCandidateHandle]; "
                + "constraints=[followUpCandidateHandle:CurrentlyAuthorizedFollowUp]";
        assertThat(unknown).isEqualTo(new AgentActionProposal.Malformed(invalidFollowUpSelection));
        assertThat(repository).isEqualTo(new AgentActionProposal.Malformed(invalidFollowUpSelection));
        assertThat(capabilityAbsent).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: requestedTool=codebase_get_source_segment; "
                        + "toolStatus=NOT_CURRENTLY_ISSUED; expected=currentlyIssuedTool"));
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
                .isEqualTo(new AgentActionProposal.Malformed(
                        "MALFORMED_ACTION_RESPONSE: requestedTool=codebase_get_source_segment; "
                                + "toolStatus=NOT_CURRENTLY_ISSUED; expected=currentlyIssuedTool"));
        assertThat(executorCalls).hasValue(0);
    }

    @Test
    void failsClosedBeforeDecodingOrExecutingForAbsentMismatchedAndModelWritableCandidateBoundCalls() {
        AtomicInteger followUpExecutorCalls = new AtomicInteger();
        PlanningToolRegistry followUpRegistry = followUpRegistry(followUpExecutorCalls);
        AtomicInteger candidateBoundExecutorCalls = new AtomicInteger();
        PlanningToolRegistry candidateBoundRegistry = candidateBoundRegistry(candidateBoundExecutorCalls);

        AgentActionProposal absentFollowUp = followUpRegistry.interpretToolCall(
                "codebase_get_source_segment", "{", contextWithoutFollowUps());
        AgentActionProposal mismatchedFollowUp = candidateBoundRegistry.interpretToolCall("candidate_bound_test",
                candidateBoundInput("candidate-bound-follow-up", 2), contextWithMismatchedFollowUpAndDirectCandidate());
        AgentActionProposal modelWritableProtectedField = candidateBoundRegistry.interpretToolCall("candidate_bound_test", """
                {"candidateHandles":["candidate-method"],
                 "questionToResolve":"Find callers",
                 "rationale":"The selected candidate defines the scope",
                 "option":2,
                 "target":"model-writable-target"}
                """, contextWithDirectSemanticCandidate());

        assertThat(absentFollowUp).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: requestedTool=codebase_get_source_segment; "
                        + "toolStatus=NOT_CURRENTLY_ISSUED; expected=currentlyIssuedTool"));
        assertThat(mismatchedFollowUp).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=candidate_bound_test; reason=CANDIDATE_SELECTION; "
                        + "invalidFields=[candidateHandles]; "
                        + "constraints=[candidateHandles:CurrentlyAuthorizedCandidate]"));
        assertThat(modelWritableProtectedField).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=candidate_bound_test; reason=JSON_CONTRACT"));
        assertThat(followUpExecutorCalls).hasValue(0);
        assertThat(candidateBoundExecutorCalls).hasValue(0);
    }

    @Test
    void projectsOnlyCurrentCandidateAuthorityForEachIssuedToolAndRejectsOtherToolFollowUps() {
        AtomicInteger executorCalls = new AtomicInteger();
        PlanningToolRegistry registry = candidateBoundRegistry(executorCalls);
        AgentPromptContext context = contextWithCandidateBoundAuthorityProjection();

        assertThat(registry.issuedTools(context))
                .filteredOn(issuedTool -> issuedTool.name().equals("candidate_bound_test"))
                .singleElement()
                .satisfies(issuedTool -> assertThat(issuedTool.allowedCandidateHandles()).containsExactly(
                        new CandidateHandleRef("candidate-bound-follow-up"),
                        new CandidateHandleRef("candidate-method")));
        assertThat(registry.interpretToolCall("candidate_bound_test",
                candidateBoundInput("candidate-other-tool-follow-up", 2), context))
                .isEqualTo(new AgentActionProposal.Malformed(
                        "INVALID_TOOL_INPUT: tool=candidate_bound_test; reason=CANDIDATE_SELECTION; "
                                + "invalidFields=[candidateHandles]; "
                                + "constraints=[candidateHandles:CurrentlyAuthorizedCandidate]"));
        assertThat(executorCalls).hasValue(0);
    }

    @Test
    void projectsOnlyExactFollowUpAuthorityForFollowUpOnlyRegistrations() {
        PlanningToolRegistry registry = followUpRegistry(new AtomicInteger());

        assertThat(registry.issuedTools(contextWithFollowUpAndRepositoryCandidate()))
                .filteredOn(issuedTool -> issuedTool.name().equals("codebase_get_source_segment"))
                .singleElement()
                .satisfies(issuedTool -> assertThat(issuedTool.allowedCandidateHandles()).containsExactly(
                        new CandidateHandleRef("candidate-follow-up")));
    }

    @Test
    void projectsCurrentRepositoryAuthorityForMapperRegistrationsAndRejectsOtherToolSelections() {
        AtomicInteger executorCalls = new AtomicInteger();
        PlanningToolRegistry registry = repositoryMapperRegistry(executorCalls);
        AgentPromptContext context = contextWithRepositoryMapperAuthority();

        assertThat(registry.issuedTools(context))
                .filteredOn(issuedTool -> issuedTool.name().equals("repository_mapper_test"))
                .singleElement()
                .satisfies(issuedTool -> assertThat(issuedTool.allowedCandidateHandles()).containsExactly(
                        new CandidateHandleRef("candidate-repository")));
        assertThat(registry.issuedRegistrations(contextWithoutRepositoryMapperCandidate()))
                .extracting(PlanningToolRegistration::name)
                .doesNotContain("repository_mapper_test");
        assertThat(registry.interpretToolCall("repository_mapper_test",
                candidateBoundInput("candidate-other-tool-follow-up", 2), context))
                .isEqualTo(new AgentActionProposal.Malformed(
                        "INVALID_TOOL_INPUT: tool=repository_mapper_test; reason=CANDIDATE_SELECTION; "
                                + "invalidFields=[candidateHandles]; "
                                + "constraints=[candidateHandles:CurrentlyAuthorizedCandidate]"));
        QueryAction action = (QueryAction) ((AgentActionProposal.Proposed) registry.interpretToolCall(
                "repository_mapper_test", candidateBoundInput("candidate-repository", 2), context)).action();
        assertThat(action.candidates()).containsExactly(new CandidateHandleRef("candidate-repository"));
        assertThat(executorCalls).hasValue(0);
    }

    @Test
    void issuesAndExecutesZeroCandidateMapperRegistrationWhenPolicyPermitsIt() {
        AtomicInteger executorCalls = new AtomicInteger();
        CapabilityPolicy policy = new CapabilityPolicy("zero_candidate_mapper_test", "v1",
                Set.of(CandidateKind.REPOSITORY), 0, 1);
        QueryPlanningMapper<TestInput, TestInput> mapper = input -> new QueryPlanningSelection<>(List.of(),
                input.questionToResolve(), input.rationale(), input);
        QueryPlanningToolRegistration<TestInput, TestInput> registration = PlanningToolRegistry.registration(policy,
                TestInput.class, TestInput.class, mapper, (executionContext, input) -> {
                    executorCalls.incrementAndGet();
                    return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
                }, payloadCodec());
        PlanningToolRegistry registry = registry(List.of(provider(List.of(registration))));
        AgentPromptContext context = followUpContext(Map.of(
                new CapabilityHandle("capability-zero-candidate-mapper", binding()), policy), Map.of());

        assertThat(registry.issuedTools(context))
                .containsExactly(new IssuedPlanningTool("zero_candidate_mapper_test", List.of()));
        QueryAction action = (QueryAction) ((AgentActionProposal.Proposed) registry.interpretToolCall(
                "zero_candidate_mapper_test", """
                        {"questionToResolve":"Inspect the repository metadata",
                         "rationale":"This query does not need a candidate"}
                        """, context)).action();
        CapabilityExecutionResult result = registry.execute(new CapabilityInvocation(policy, List.of(),
                action.questionToResolve(), action.payload(), binding().revisionVector()));

        assertThat(action.candidates()).isEmpty();
        assertThat(result).isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(executorCalls).hasValue(1);
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

    @Test
    void projectsProtectedExecutionFieldsFromTheCurrentSelectedCandidate() {
        AtomicInteger executorCalls = new AtomicInteger();
        PlanningToolRegistry registry = candidateBoundRegistry(executorCalls);
        String rawInput = """
                {"candidateHandles":["candidate-method"],
                 "questionToResolve":"Find callers",
                 "rationale":"The selected method is the requested scope",
                 "option":2}
                """;

        AgentActionProposal proposal = registry.interpretToolCall("candidate_bound_test", rawInput,
                contextWithDirectSemanticCandidate());

        assertThat(registry.issuedRegistrations(contextWithDirectSemanticCandidate()))
                .extracting(PlanningToolRegistration::name)
                .containsExactly("candidate_bound_test");
        QueryAction action = (QueryAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.candidates()).containsExactly(new CandidateHandleRef("candidate-method"));
        assertThat(action.questionToResolve()).isEqualTo("Find callers");
        assertThat(action.rationale()).isEqualTo("The selected method is the requested scope");
        assertThat(action.payload()).isEqualTo(payloadCodec().encode(new TestExecutionInput("runtime-owned-target", 2)));
        assertThat(rawInput).doesNotContain("runtime-owned-target");
        assertThat(executorCalls).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("candidateBoundAuthorityBoundaries")
    void enforcesCandidateBoundAuthorityBeforeAnyExecutorInvocation(
            String scenario,
            AgentPromptContext context,
            String selectedCandidateHandle,
            boolean issued,
            String expectedMalformedReason) {
        AtomicInteger executorCalls = new AtomicInteger();
        PlanningToolRegistry registry = candidateBoundRegistry(executorCalls);

        AgentActionProposal proposal = registry.interpretToolCall("candidate_bound_test",
                candidateBoundInput(selectedCandidateHandle, 2), context);

        assertThat(registry.issuedRegistrations(context).stream().map(PlanningToolRegistration::name)
                .anyMatch("candidate_bound_test"::equals)).isEqualTo(issued);
        if (Optional.ofNullable(expectedMalformedReason).isPresent()) {
            assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(expectedMalformedReason));
        } else {
            QueryAction action = (QueryAction) ((AgentActionProposal.Proposed) proposal).action();
            assertThat(action.payload()).isEqualTo(payloadCodec().encode(
                    new TestExecutionInput("provider-owned-target", 2)));
        }
        assertThat(executorCalls).hasValue(0);
    }

    private static Stream<Arguments> candidateBoundAuthorityBoundaries() {
        String selectionFailure = "INVALID_TOOL_INPUT: tool=candidate_bound_test; "
                + "reason=CANDIDATE_SELECTION; invalidFields=[candidateHandles]; "
                + "constraints=[candidateHandles:CurrentlyAuthorizedCandidate]";
        String notIssued = "MALFORMED_ACTION_RESPONSE: requestedTool=candidate_bound_test; "
                + "toolStatus=NOT_CURRENTLY_ISSUED; expected=currentlyIssuedTool";
        return Stream.of(
                Arguments.of("does not issue without a compatible candidate", contextWithoutCandidateBoundCandidates(),
                        "unknown-candidate", false, notIssued),
                Arguments.of("uses the matching current follow-up payload", contextWithCandidateBoundFollowUp(),
                        "candidate-bound-follow-up", true, null),
                Arguments.of("rejects a mismatched follow-up without direct fallback",
                        contextWithMismatchedFollowUpAndDirectCandidate(), "candidate-bound-follow-up", true,
                        selectionFailure),
                Arguments.of("does not issue stale candidate bindings", contextWithStaleDirectSemanticCandidate(),
                        "candidate-semantic-target-old", false, notIssued));
    }

    @ParameterizedTest(name = "rejects blank {0} without leaking model input")
    @MethodSource("candidateBoundBlankTextInputs")
    void rejectsBlankCandidateBoundQuestionAndRationaleAsSafeInvalidToolInput(
            String invalidField,
            String questionToResolve,
            String rationale) {
        AtomicInteger executorCalls = new AtomicInteger();
        PlanningToolRegistry registry = candidateBoundRegistry(executorCalls);

        AgentActionProposal proposal = registry.interpretToolCall("candidate_bound_test",
                candidateBoundInput("candidate-sensitive-handle", questionToResolve, rationale, 2),
                contextWithDirectSemanticCandidate());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=candidate_bound_test; reason=CANDIDATE_INPUT; "
                        + "invalidFields=[%s]; constraints=[%s:NotBlank]".formatted(invalidField, invalidField)));
        assertThat(proposal.toString()).doesNotContain("candidate-sensitive-handle", "question-secret", "rationale-secret");
        assertThat(executorCalls).hasValue(0);
    }

    private static Stream<Arguments> candidateBoundBlankTextInputs() {
        return Stream.of(
                Arguments.of("questionToResolve", "   ", "rationale-secret"),
                Arguments.of("rationale", "question-secret", "  "));
    }

    @ParameterizedTest(name = "rejects candidate cardinality {0}")
    @MethodSource("candidateBoundIncompatibleCardinalities")
    void rejectsCandidateBoundRegistrationWhenPolicyCannotRepresentOneSelectedCandidate(
            String scenario,
            int minimumCandidates,
            int maximumCandidates) {
        assertThatThrownBy(() -> PlanningToolRegistry.candidateBoundRegistration(
                PlanningToolCategory.QUERY, candidateBoundPolicy(minimumCandidates, maximumCandidates),
                TestCandidateBoundInput.class, TestExecutionInput.class, candidateBoundPlanner(),
                (executionContext, input) -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()),
                payloadCodec()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate-bound capability must permit exactly one candidate");
    }

    private static Stream<Arguments> candidateBoundIncompatibleCardinalities() {
        return Stream.of(
                Arguments.of("below one", 0, 0),
                Arguments.of("above one", 2, 2));
    }

    private static PlanningToolRegistry registry() {
        CanonicalCapabilityPayloadCodec payloadCodec = payloadCodec();
        return registry(List.of(new CorePlanningToolProvider(), provider(List.of(
                queryRegistration("query_tool", "v1", payloadCodec)))));
    }

    private static PlanningToolRegistry registryWithExecutePreview() {
        CanonicalCapabilityPayloadCodec payloadCodec = payloadCodec();
        return registry(List.of(new CorePlanningToolProvider(), provider(List.of(
                queryRegistration("query_tool", "v1", payloadCodec),
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

    private static PlanningToolRegistry candidateBoundRegistry(AtomicInteger executorCalls) {
        return registry(List.of(provider(List.of(PlanningToolRegistry.candidateBoundRegistration(
                PlanningToolCategory.QUERY, candidateBoundPolicy(), TestCandidateBoundInput.class, TestExecutionInput.class,
                candidateBoundPlanner(),
                (executionContext, input) -> {
                    executorCalls.incrementAndGet();
                    return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
                }, payloadCodec())))));
    }

    private static PlanningToolRegistry repositoryMapperRegistry(AtomicInteger executorCalls) {
        CapabilityPolicy policy = new CapabilityPolicy("repository_mapper_test", "v1", Set.of(CandidateKind.REPOSITORY),
                1, 1);
        QueryPlanningMapper<TestCandidateBoundInput, TestExecutionInput> mapper = input -> new QueryPlanningSelection<>(
                input.candidateHandles().stream().map(CandidateHandleRef::new).toList(), input.questionToResolve(),
                input.rationale(), new TestExecutionInput("mapper-owned-target", input.option()));
        QueryPlanningToolRegistration<TestCandidateBoundInput, TestExecutionInput> registration =
                PlanningToolRegistry.registration(policy, TestCandidateBoundInput.class, TestExecutionInput.class, mapper,
                        (executionContext, input) -> {
                            executorCalls.incrementAndGet();
                            return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
                        }, payloadCodec());
        return registry(List.of(provider(List.of(registration))));
    }

    private static CandidateBoundExecutionPlanner<TestCandidateBoundInput, TestExecutionInput> candidateBoundPlanner() {
        return
                new CandidateBoundExecutionPlanner<>() {
                    @Override
                    public boolean supportsDirectCandidate(com.java.system.agent.answering.domain.candidate.AnalysisCandidate candidate) {
                        return candidate instanceof SemanticTargetCandidate;
                    }

                    @Override
                    public TestExecutionInput planDirect(
                            TestCandidateBoundInput input,
                            com.java.system.agent.answering.domain.candidate.AnalysisCandidate candidate) {
                        return new TestExecutionInput("runtime-owned-target", input.option());
                    }

                    @Override
                    public TestExecutionInput planFollowUp(
                            TestCandidateBoundInput input,
                            TestExecutionInput providerInput) {
                        return new TestExecutionInput(providerInput.target(), input.option());
                    }
                };
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
        return context(usedExecuteExecutions, questionPlanInteractions());
    }

    private static AgentPromptContext planningContext() {
        return context(0, List.of());
    }

    private static AgentPromptContext context(int usedExecuteExecutions, List<ModelInteraction> modelInteractions) {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        return new AgentPromptContext("Find routes", SessionHistory.empty(), runId, attemptId, Map.of(), Map.of(), Map.of(),
                Map.of(), modelInteractions, Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, usedExecuteExecutions, 3, 0, 1, 0));
    }

    private static QuestionPlan questionPlan() {
        return new QuestionPlan(List.of(new InformationNeed(new InformationNeedId("scope"), "確認業務範圍")));
    }

    private static AgentPromptContext contextWithoutFollowUps() {
        return followUpContext(Map.of(sourceSegmentCapabilityHandle(), sourceSegmentPolicy()), Map.of());
    }

    private static AgentPromptContext contextWithoutCandidateBoundCandidates() {
        return followUpContext(Map.of(candidateBoundCapabilityHandle(), candidateBoundPolicy()), Map.of());
    }

    private static AgentPromptContext contextWithDirectSemanticCandidate() {
        CandidateHandle candidateHandle = directSemanticCandidateHandle();
        return followUpContext(Map.of(candidateBoundCapabilityHandle(), candidateBoundPolicy()), Map.of(candidateHandle,
                new IssuedCandidate(candidateHandle, directSemanticCandidate())));
    }

    private static AgentPromptContext contextWithCandidateBoundFollowUp() {
        CandidateHandle candidateHandle = candidateBoundFollowUpHandle();
        return followUpContext(Map.of(candidateBoundCapabilityHandle(), candidateBoundPolicy()), Map.of(candidateHandle,
                new IssuedCandidate(candidateHandle, candidateBoundFollowUp())));
    }

    private static AgentPromptContext contextWithMismatchedFollowUpAndDirectCandidate() {
        CandidateHandle followUpHandle = candidateBoundFollowUpHandle();
        CandidateHandle directHandle = directSemanticCandidateHandle();
        return followUpContext(Map.of(candidateBoundCapabilityHandle(), candidateBoundPolicy()), Map.of(
                followUpHandle, new IssuedCandidate(followUpHandle, mismatchedCandidateBoundFollowUp()),
                directHandle, new IssuedCandidate(directHandle, directSemanticCandidate())));
    }

    private static AgentPromptContext contextWithCandidateBoundAuthorityProjection() {
        CandidateHandle matchingFollowUpHandle = candidateBoundFollowUpHandle();
        CandidateHandle directHandle = directSemanticCandidateHandle();
        CandidateHandle otherToolFollowUpHandle = new CandidateHandle("candidate-other-tool-follow-up", binding(),
                CandidateKind.FOLLOW_UP);
        CandidateHandle staleHandle = new CandidateHandle("candidate-stale", new HandleBinding(
                new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-0"), binding().revisionVector()),
                CandidateKind.SEMANTIC_TARGET);
        Map<CandidateHandle, IssuedCandidate> candidates = new LinkedHashMap<>();
        candidates.put(matchingFollowUpHandle, new IssuedCandidate(matchingFollowUpHandle, candidateBoundFollowUp()));
        candidates.put(directHandle, new IssuedCandidate(directHandle, directSemanticCandidate()));
        candidates.put(otherToolFollowUpHandle, new IssuedCandidate(otherToolFollowUpHandle,
                mismatchedCandidateBoundFollowUp()));
        candidates.put(staleHandle, new IssuedCandidate(staleHandle, directSemanticCandidate()));
        return followUpContext(Map.of(candidateBoundCapabilityHandle(), candidateBoundPolicy()), candidates);
    }

    private static AgentPromptContext contextWithRepositoryMapperAuthority() {
        CapabilityPolicy policy = new CapabilityPolicy("repository_mapper_test", "v1", Set.of(CandidateKind.REPOSITORY),
                1, 1);
        CapabilityHandle capability = new CapabilityHandle("capability-repository-mapper", binding());
        CandidateHandle repositoryHandle = new CandidateHandle("candidate-repository", binding(), CandidateKind.REPOSITORY);
        CandidateHandle semanticHandle = directSemanticCandidateHandle();
        CandidateHandle otherToolFollowUpHandle = new CandidateHandle("candidate-other-tool-follow-up", binding(),
                CandidateKind.FOLLOW_UP);
        Map<CandidateHandle, IssuedCandidate> candidates = new LinkedHashMap<>();
        candidates.put(repositoryHandle, new IssuedCandidate(repositoryHandle,
                new RepositoryCandidate(repositoryId(), "Repository root")));
        candidates.put(semanticHandle, new IssuedCandidate(semanticHandle, directSemanticCandidate()));
        candidates.put(otherToolFollowUpHandle, new IssuedCandidate(otherToolFollowUpHandle,
                mismatchedCandidateBoundFollowUp()));
        return followUpContext(Map.of(capability, policy), candidates);
    }

    private static AgentPromptContext contextWithoutRepositoryMapperCandidate() {
        CapabilityPolicy policy = new CapabilityPolicy("repository_mapper_test", "v1", Set.of(CandidateKind.REPOSITORY),
                1, 1);
        CapabilityHandle capability = new CapabilityHandle("capability-repository-mapper", binding());
        CandidateHandle semanticHandle = directSemanticCandidateHandle();
        return followUpContext(Map.of(capability, policy), Map.of(semanticHandle,
                new IssuedCandidate(semanticHandle, directSemanticCandidate())));
    }

    private static AgentPromptContext contextWithStaleDirectSemanticCandidate() {
        HandleBinding oldBinding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-0"),
                RevisionVector.empty().pin(repositoryId(), new RepositoryRevision("revision-1")));
        CandidateHandle oldHandle = new CandidateHandle("candidate-semantic-target-old", oldBinding,
                CandidateKind.SEMANTIC_TARGET);
        return followUpContext(Map.of(candidateBoundCapabilityHandle(), candidateBoundPolicy()), Map.of(oldHandle,
                new IssuedCandidate(oldHandle, directSemanticCandidate())));
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
                new IssuedCandidate(oldHandle, followUpCandidate())), Map.of(), Map.of(), questionPlanInteractions(), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static AgentPromptContext followUpContext(
            Map<CapabilityHandle, CapabilityPolicy> capabilities,
            Map<CandidateHandle, IssuedCandidate> candidates) {
        return new AgentPromptContext("Find routes", SessionHistory.empty(), new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"), capabilities, candidates, Map.of(), Map.of(), questionPlanInteractions(), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static List<ModelInteraction> questionPlanInteractions() {
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        QuestionPlan plan = questionPlan();
        return List.of(
                new ModelInteraction.ActionSelected(attemptId, new PlanAction(plan)),
                new ModelInteraction.ActionResultRecorded(attemptId, new ActionResult.QuestionPlanRecorded(plan)));
    }

    private static CapabilityPolicy sourceSegmentPolicy() {
        return new CapabilityPolicy("codebase_get_source_segment", "v1", Set.of(CandidateKind.FOLLOW_UP), 1, 1);
    }

    private static CapabilityPolicy candidateBoundPolicy() {
        return candidateBoundPolicy(0, 1);
    }

    private static CapabilityPolicy candidateBoundPolicy(int minimumCandidates, int maximumCandidates) {
        return new CapabilityPolicy("candidate_bound_test", "v1",
                Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP), minimumCandidates, maximumCandidates);
    }

    private static CapabilityHandle candidateBoundCapabilityHandle() {
        return new CapabilityHandle("capability-candidate-bound", binding());
    }

    private static CandidateHandle directSemanticCandidateHandle() {
        return new CandidateHandle("candidate-method", binding(), CandidateKind.SEMANTIC_TARGET);
    }

    private static CandidateHandle candidateBoundFollowUpHandle() {
        return new CandidateHandle("candidate-bound-follow-up", binding(), CandidateKind.FOLLOW_UP);
    }

    private static SemanticTargetCandidate directSemanticCandidate() {
        return new SemanticTargetCandidate(repositoryId(), new RepositoryRevision("revision-1"),
                new SemanticTarget(SemanticTargetKind.SYMBOL, "runtime-owned-target", Optional.empty()),
                "Selected method");
    }

    private static FollowUpCandidate candidateBoundFollowUp() {
        return new FollowUpCandidate(repositoryId(), new RepositoryRevision("revision-1"), "candidate_bound_test", "v1",
                payloadCodec().encode(new TestExecutionInput("provider-owned-target", 1)), "Provider continuation");
    }

    private static FollowUpCandidate mismatchedCandidateBoundFollowUp() {
        return new FollowUpCandidate(repositoryId(), new RepositoryRevision("revision-1"), "other_capability", "v2",
                payloadCodec().encode(new TestExecutionInput("provider-owned-target", 1)), "Mismatched continuation");
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

    private static String candidateBoundInput(String handle, int option) {
        return candidateBoundInput(handle, "Find callers", "The selected candidate defines the scope", option);
    }

    private static String candidateBoundInput(String handle, String questionToResolve, String rationale, int option) {
        return """
                {"candidateHandles":["%s"],
                 "questionToResolve":"%s",
                 "rationale":"%s",
                 "option":%d}
                """.formatted(handle, questionToResolve, rationale, option);
    }

    private static CanonicalCapabilityPayloadCodec payloadCodec() {
        return new CanonicalCapabilityPayloadCodec(Validation.buildDefaultValidatorFactory().getValidator());
    }

    private record TestInput(
            @JsonProperty(required = true) String questionToResolve,
            @JsonProperty(required = true) String rationale) {
    }

    private record TestCandidateBoundInput(
            @JsonProperty(required = true) List<String> candidateHandles,
            @JsonProperty(required = true) String questionToResolve,
            @JsonProperty(required = true) String rationale,
            @JsonProperty(required = true) int option) implements CandidateBoundPlanningInput {
    }

    private record TestExecutionInput(
            @JsonProperty(required = true) String target,
            @JsonProperty(required = true) int option) {
    }
}
