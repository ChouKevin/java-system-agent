package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.CorePlanningToolProvider;
import com.java.system.agent.capability.planning.FollowUpOnlyQueryRegistration;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolCategory;
import com.java.system.agent.capability.planning.PlanningToolRegistration;
import com.java.system.agent.capability.planning.QueryCapabilityRegistration;
import com.java.system.agent.capability.planning.QueryPlanningToolRegistration;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.codeintelligence.CodeIntelligenceQuery;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticCandidateTargetMapper;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticResultMapper;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驗證 repository scoped code intelligence query 必須選定一個 repository
 */
class CodeIntelligencePlanningToolProviderTest {

    @Test
    void requiresExactlyOneRepositoryForRepositoryScopedQueries() {
        CodeIntelligencePlanningToolProvider provider = new CodeIntelligencePlanningToolProvider(
                mock(JavaSemanticServiceHttpAdapter.class), new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator()));

        List<CapabilityPolicy> policies = provider.registrations().stream()
                .filter(registration -> registration instanceof QueryCapabilityRegistration<?>)
                .map(registration -> (QueryCapabilityRegistration<?>) registration)
                .map(QueryCapabilityRegistration::policy)
                .filter(policy -> policy.name().equals("codebase_list_entry_points")
                        || policy.name().equals("codebase_lookup_api_route")
                        || policy.name().equals("codebase_suggest_api_route"))
                .toList();

        assertThat(policies)
                .extracting(CapabilityPolicy::name, CapabilityPolicy::minimumCandidates,
                        CapabilityPolicy::maximumCandidates)
                .containsExactlyInAnyOrder(
                        tuple("codebase_list_entry_points", 1, 1),
                        tuple("codebase_lookup_api_route", 1, 1),
                        tuple("codebase_suggest_api_route", 1, 1));
    }

    @Test
    void keepsTheM5CodeIntelligenceToolsAtTheStartOfStableProviderOrder() {
        CodeIntelligencePlanningToolProvider provider = new CodeIntelligencePlanningToolProvider(
                mock(JavaSemanticServiceHttpAdapter.class), new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator()));

        assertThat(provider.registrations())
                .map(registration -> (QueryCapabilityRegistration<?>) registration)
                .map(QueryCapabilityRegistration::policy)
                .extracting(CapabilityPolicy::name)
                .startsWith(
                        "codebase_list_entry_points",
                        "codebase_lookup_api_route",
                        "codebase_suggest_api_route",
                        "codebase_outgoing_call_graph",
                        "codebase_incoming_call_graph");
    }

    @Test
    void registersSemanticQueryCatalogWithDirectAndBoundFollowUpContracts() {
        CodeIntelligencePlanningToolProvider provider = new CodeIntelligencePlanningToolProvider(
                mock(JavaSemanticServiceHttpAdapter.class), new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator()));

        assertThat(provider.registrations())
                .map(registration -> (QueryCapabilityRegistration<?>) registration)
                .map(QueryCapabilityRegistration::policy)
                .extracting(CapabilityPolicy::name)
                .containsExactly(
                        CodeIntelligenceQuery.LIST_ENTRY_POINTS.capabilityName(),
                        CodeIntelligenceQuery.LOOKUP_API_ROUTE.capabilityName(),
                        CodeIntelligenceQuery.SUGGEST_API_ROUTE.capabilityName(),
                        CodeIntelligenceQuery.OUTGOING_CALL_GRAPH.capabilityName(),
                        CodeIntelligenceQuery.INCOMING_CALL_GRAPH.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName(),
                        CodeIntelligenceQuery.RESOLVE_CONCEPT.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName(),
                        CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES.capabilityName(),
                        CodeIntelligenceQuery.GET_EVIDENCE_SOURCE.capabilityName(),
                        CodeIntelligenceQuery.GET_METHOD_SOURCE.capabilityName(),
                        CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName(),
                        CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL.capabilityName());
        assertThat(provider.registrations())
                .filteredOn(registration -> registration instanceof QueryPlanningToolRegistration<?, ?>)
                .extracting(registration -> registration.name())
                .containsExactly(
                        CodeIntelligenceQuery.LIST_ENTRY_POINTS.capabilityName(),
                        CodeIntelligenceQuery.LOOKUP_API_ROUTE.capabilityName(),
                        CodeIntelligenceQuery.SUGGEST_API_ROUTE.capabilityName(),
                        CodeIntelligenceQuery.OUTGOING_CALL_GRAPH.capabilityName(),
                        CodeIntelligenceQuery.INCOMING_CALL_GRAPH.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName(),
                        CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES.capabilityName(),
                        CodeIntelligenceQuery.GET_METHOD_SOURCE.capabilityName(),
                        CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName(),
                        CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL.capabilityName());
        List<String> followUpOnlyNames = provider.registrations().stream()
                .filter(registration -> registration instanceof FollowUpOnlyQueryRegistration<?>)
                .map(PlanningToolRegistration::name)
                .toList();
        assertThat(followUpOnlyNames).containsExactlyInAnyOrder(
                CodeIntelligenceQuery.RESOLVE_CONCEPT.capabilityName(),
                CodeIntelligenceQuery.GET_EVIDENCE_SOURCE.capabilityName());

        Map<String, Set<CandidateKind>> candidateKinds = provider.registrations().stream()
                .map(registration -> (QueryCapabilityRegistration<?>) registration)
                .map(QueryCapabilityRegistration::policy)
                .collect(java.util.stream.Collectors.toMap(CapabilityPolicy::name, CapabilityPolicy::acceptedCandidateKinds));
        assertThat(candidateKinds).containsAllEntriesOf(Map.ofEntries(
                Map.entry(CodeIntelligenceQuery.OUTGOING_CALL_GRAPH.capabilityName(), Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.INCOMING_CALL_GRAPH.capabilityName(), Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName(), Set.of(CandidateKind.REPOSITORY, CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.RESOLVE_CONCEPT.capabilityName(), Set.of(CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS.capabilityName(), Set.of(CandidateKind.REPOSITORY, CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName(),
                        Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName(),
                        Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES.capabilityName(), Set.of(CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.GET_EVIDENCE_SOURCE.capabilityName(), Set.of(CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.GET_METHOD_SOURCE.capabilityName(), Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName(),
                        Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL.capabilityName(), Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP))));
        assertThat(provider.registrations())
                .map(registration -> (QueryCapabilityRegistration<?>) registration)
                .map(QueryCapabilityRegistration::policy)
                .allSatisfy(policy -> {
                    assertThat(policy.version()).isEqualTo(CodeIntelligenceQuery.LIST_ENTRY_POINTS.version());
                    assertThat(policy.minimumCandidates()).isEqualTo(1);
                    assertThat(policy.maximumCandidates()).isEqualTo(1);
                });
    }

    @Test
    void exposesExactQueryDescriptorsAndOnlyTheCanonicalGuidanceIds() {
        CodeIntelligencePlanningToolProvider provider = new CodeIntelligencePlanningToolProvider(
                mock(JavaSemanticServiceHttpAdapter.class), new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator()));

        assertThat(provider.registrations())
                .allSatisfy(registration -> assertThat(registration.descriptor().toolName())
                        .isEqualTo(registration.name()));
        assertThat(provider.registrations())
                .allSatisfy(registration -> {
                    QueryCapabilityRegistration<?> queryRegistration = (QueryCapabilityRegistration<?>) registration;
                    assertThat(registration.descriptor().capability()).contains(queryRegistration.policy());
                });
        List<Map.Entry<String, String>> guidanceIds = provider.registrations().stream()
                .map(registration -> registration.descriptor().guidanceId()
                        .map(guidanceId -> Map.entry(registration.name(), guidanceId)))
                .flatMap(Optional::stream)
                .toList();

        assertThat(guidanceIds).containsExactlyInAnyOrder(
                Map.entry("codebase_discover_concepts", "codebase_discover_concepts"),
                Map.entry("codebase_discover_type_members", "codebase_discover_type_members"));
    }

    @Test
    void retainsTheCandidateKindContractForDirectQueryTools() {
        CodeIntelligencePlanningToolProvider provider = new CodeIntelligencePlanningToolProvider(
                mock(JavaSemanticServiceHttpAdapter.class), new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator()));

        assertThat(provider.registrations())
                .filteredOn(registration -> registration.name().equals(
                        CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName()))
                .singleElement()
                .satisfies(registration -> assertThat(((QueryCapabilityRegistration<?>) registration)
                        .policy().acceptedCandidateKinds())
                        .containsExactlyInAnyOrder(CandidateKind.FOLLOW_UP, CandidateKind.REPOSITORY));
    }

    @Test
    void plansMethodImplementationDiscoveryFromEitherAnExactSemanticCandidateOrProviderFollowUp() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                new CorePlanningToolProvider(),
                new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
        CapabilityPolicy policy = policy(registry, CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS);
        DiscoverMethodImplementationsExecutionInput input = new DiscoverMethodImplementationsExecutionInput(
                Optional.of(graphTarget()));
        IssuedCandidate followUp = followUpCandidate("candidate-implementations", binding, repositoryId, revision,
                policy, payloadCodec.encode(input));
        IssuedCandidate direct = semanticTargetCandidate("candidate-implementation-target", binding, repositoryId, revision);
        AgentPromptContext directContext = promptContext(policy, direct, binding);
        AgentPromptContext followUpContext = promptContext(policy, followUp, binding);
        CapabilityExecutionResult expected = new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        when(adapter.discoverMethodImplementations(any(CapabilityExecutionContext.class), eq(input)))
                .thenReturn(expected);

        assertThat(policy.acceptedCandidateKinds())
                .containsExactlyInAnyOrder(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP);
        assertThat(registry.issuedRegistrations(directContext))
                .extracting(registration -> registration.name())
                .contains(CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName());

        QueryAction directAction = queryAction(registry.interpretToolCall(policy.name(), """
                {"candidateHandles":["candidate-implementation-target"],"questionToResolve":"Find implementations","rationale":"Inspect the selected method declaration"}
                """, directContext));
        QueryAction followUpAction = queryAction(registry.interpretToolCall(policy.name(), """
                {"candidateHandles":["candidate-implementations"],"questionToResolve":"Find implementations","rationale":"Provider authorized this declaration"}
                """, followUpContext));

        assertThat(payloadCodec.decode(directAction.payload(), DiscoverMethodImplementationsExecutionInput.class))
                .isEqualTo(input);
        assertThat(followUpAction.payload()).isEqualTo(payloadCodec.encode(input));
        assertThat(execute(registry, policy, direct, directAction, revisions)).isSameAs(expected);
        assertThat(execute(registry, policy, followUp, followUpAction, revisions)).isSameAs(expected);
        verify(adapter, times(2)).discoverMethodImplementations(any(CapabilityExecutionContext.class), eq(input));
    }

    @Test
    void plansTypeMemberDiscoveryFromMethodTargetsAndPreservesContinuationAuthority() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
        CapabilityPolicy policy = policy(registry, CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS);
        IssuedCandidate direct = semanticTargetCandidate("candidate-method", binding, repositoryId, revision);
        DiscoverTypeMembersExecutionInput directInput = new DiscoverTypeMembersExecutionInput(graphTarget().sourceType(),
                List.of("FIELD"), Optional.of("order"), 0, 7);
        DiscoverTypeMembersExecutionInput firstPage = new DiscoverTypeMembersExecutionInput(graphTarget().sourceType(),
                List.of("METHOD"), Optional.of("find"), 0, 1);
        DiscoverTypeMembersExecutionInput continuation = new DiscoverTypeMembersExecutionInput(graphTarget().sourceType(),
                List.of("METHOD"), Optional.of("find"), 10, 7);
        IssuedCandidate firstPageFollowUp = followUpCandidate("candidate-members-first", binding, repositoryId, revision,
                policy, payloadCodec.encode(firstPage));
        IssuedCandidate continuationFollowUp = followUpCandidate("candidate-members-next", binding, repositoryId, revision,
                policy, payloadCodec.encode(continuation));

        assertThat(registry.issuedRegistrations(promptContext(policy, direct, binding)))
                .extracting(registration -> registration.name())
                .contains(policy.name());

        QueryAction directAction = queryAction(registry.interpretToolCall(policy.name(), """
                {"candidateHandles":["candidate-method"],"questionToResolve":"Inspect fields","rationale":"Read the owning type","initialFilter":{"memberKinds":["FIELD"],"namePrefix":"order"},"limit":7}
                """, promptContext(policy, direct, binding)));
        QueryAction firstPageAction = queryAction(registry.interpretToolCall(policy.name(), """
                {"candidateHandles":["candidate-members-first"],"questionToResolve":"Inspect fields","rationale":"Refine the first page","initialFilter":{"memberKinds":["FIELD"],"namePrefix":"order"},"limit":7}
                """, promptContext(policy, firstPageFollowUp, binding)));
        QueryAction continuationAction = queryAction(registry.interpretToolCall(policy.name(), """
                {"candidateHandles":["candidate-members-next"],"questionToResolve":"Inspect more methods","rationale":"Continue the provider page","limit":9}
                """, promptContext(policy, continuationFollowUp, binding)));

        assertThat(payloadCodec.decode(directAction.payload(), DiscoverTypeMembersExecutionInput.class))
                .isEqualTo(directInput);
        assertThat(payloadCodec.decode(firstPageAction.payload(), DiscoverTypeMembersExecutionInput.class))
                .isEqualTo(directInput);
        assertThat(payloadCodec.decode(continuationAction.payload(), DiscoverTypeMembersExecutionInput.class))
                .isEqualTo(new DiscoverTypeMembersExecutionInput(graphTarget().sourceType(), List.of("METHOD"),
                        Optional.of("find"), 10, 9));
        assertThat(registry.interpretToolCall(policy.name(), """
                {"candidateHandles":["candidate-members-next"],"questionToResolve":"Inspect fields","rationale":"Mutate the continuation","initialFilter":{"memberKinds":["FIELD"],"namePrefix":"order"},"limit":9}
                """, promptContext(policy, continuationFollowUp, binding)))
                .isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT: tool=codebase_discover_type_members; "
                        + "reason=CANDIDATE_INPUT; invalidFields=[initialFilter]; "
                        + "constraints=[initialFilter:ContinuationFilter]"));
        verify(adapter, times(0)).discoverTypeMembers(any(CapabilityExecutionContext.class), any());
    }

    @Test
    void rejectsUnsafeTypeMemberFiltersBeforeTheAdapter() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
        CapabilityPolicy policy = policy(registry, CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS);
        IssuedCandidate direct = semanticTargetCandidate("candidate-method", binding, repositoryId, revision);
        DiscoverTypeMembersExecutionInput firstPage = new DiscoverTypeMembersExecutionInput(graphTarget().sourceType(),
                List.of("METHOD"), Optional.of("find"), 0, 1);
        IssuedCandidate followUp = followUpCandidate("candidate-members-first", binding, repositoryId, revision,
                policy, payloadCodec.encode(firstPage));

        assertAll(
                () -> assertInvalidTypeMemberFilter(registry.interpretToolCall(policy.name(), """
                        {"candidateHandles":["candidate-method"],"questionToResolve":"Inspect fields","rationale":"Read the owning type","initialFilter":{"memberKinds":["FIELD","FIELD"]}}
                        """, promptContext(policy, direct, binding))),
                () -> assertInvalidTypeMemberFilter(registry.interpretToolCall(policy.name(), """
                        {"candidateHandles":["candidate-members-first"],"questionToResolve":"Inspect fields","rationale":"Refine the first page","initialFilter":{"memberKinds":["FIELD"],"namePrefix":" "}}
                        """, promptContext(policy, followUp, binding))));

        verify(adapter, times(0)).discoverTypeMembers(any(CapabilityExecutionContext.class), any());
    }

    @Test
    void plansSourceSegmentsOnlyFromExactRangesAndRetainsFollowUpLocation() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
        CapabilityPolicy policy = policy(registry, CodeIntelligenceQuery.GET_SOURCE_SEGMENT);
        SemanticDtos.SourceRangePayload location = new SemanticDtos.SourceRangePayload("src/Orders.java",
                new SemanticDtos.TextRangePayload(new SemanticDtos.Position(0, 1), new SemanticDtos.Position(2, 3)));
        IssuedCandidate sourceRange = sourceRangeCandidate("candidate-range", binding, repositoryId, revision, location);
        IssuedCandidate method = semanticTargetCandidate("candidate-method", binding, repositoryId, revision);
        GetSourceSegmentExecutionInput providerInput = new GetSourceSegmentExecutionInput(location, 2);
        IssuedCandidate followUp = followUpCandidate("candidate-segment", binding, repositoryId, revision, policy,
                payloadCodec.encode(providerInput));

        assertThat(registry.issuedRegistrations(promptContext(policy, sourceRange, binding)))
                .extracting(registration -> registration.name()).contains(policy.name());
        assertThat(registry.issuedRegistrations(promptContext(policy, method, binding)))
                .extracting(registration -> registration.name()).doesNotContain(policy.name());

        QueryAction directAction = queryAction(registry.interpretToolCall(policy.name(), """
                {"candidateHandles":["candidate-range"],"questionToResolve":"Read this range","rationale":"Inspect the selected source","contextLines":3}
                """, promptContext(policy, sourceRange, binding)));
        QueryAction followUpAction = queryAction(registry.interpretToolCall(policy.name(), """
                {"candidateHandles":["candidate-segment"],"questionToResolve":"Read surrounding source","rationale":"Expand the provider segment","contextLines":4}
                """, promptContext(policy, followUp, binding)));

        assertThat(payloadCodec.decode(directAction.payload(), GetSourceSegmentExecutionInput.class))
                .isEqualTo(new GetSourceSegmentExecutionInput(location, 3));
        assertThat(payloadCodec.decode(followUpAction.payload(), GetSourceSegmentExecutionInput.class))
                .isEqualTo(new GetSourceSegmentExecutionInput(location, 4));
    }

    @Test
    void executesAFollowUpGraphThroughRegistryDecodingAndTheRegisteredExecutor() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        SemanticDtos.MethodTargetPayload target = new SemanticDtos.MethodTargetPayload(
                new SemanticDtos.SourceTypeIdentityPayload(
                        new SemanticDtos.JavaTypeIdentityPayload("com.example", "OrderService"),
                        "src/OrderService.java"), "find", List.of("java.lang.String"));
        OutgoingCallGraphExecutionInput input = new OutgoingCallGraphExecutionInput(1, Optional.of(target));
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        CapabilityPolicy policy = registry.availableCapabilities().stream()
                .filter(candidate -> candidate.name().equals(CodeIntelligenceQuery.OUTGOING_CALL_GRAPH.capabilityName()))
                .findFirst().orElseThrow();
        CapabilityInputPayload payload = payloadCodec.encode(input);
        FollowUpCandidate followUp = new FollowUpCandidate(repositoryId, revision, policy.name(), policy.version(), payload,
                "Continue graph discovery");
        IssuedCandidate candidate = new IssuedCandidate(new CandidateHandle("candidate-follow-up",
                new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions),
                CandidateKind.FOLLOW_UP), followUp);
        CapabilityInvocation invocation = new CapabilityInvocation(policy, List.of(candidate), "Trace call graph", payload,
                revisions);
        CapabilityExecutionResult expected = new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        when(adapter.outgoingCallGraph(any(CapabilityExecutionContext.class), eq(input))).thenReturn(expected);

        CapabilityExecutionResult result = registry.execute(invocation);

        ArgumentCaptor<CapabilityExecutionContext> context = ArgumentCaptor.forClass(CapabilityExecutionContext.class);
        assertThat(result).isSameAs(expected);
        verify(adapter, times(1)).outgoingCallGraph(context.capture(), eq(input));
        assertThat(context.getValue().capability()).isEqualTo(policy);
        assertThat(context.getValue().candidates()).containsExactly(candidate);
        assertThat(context.getValue().expectedRevisions()).isEqualTo(revisions);
    }

    @Test
    void preservesProviderFollowUpAuthorityWhilePermittingBoundedQueryTuning() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
        CapabilityPolicy graphPolicy = policy(registry, CodeIntelligenceQuery.OUTGOING_CALL_GRAPH);
        CapabilityPolicy conceptsPolicy = policy(registry, CodeIntelligenceQuery.DISCOVER_CONCEPTS);
        CapabilityPolicy listenersPolicy = policy(registry, CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS);
        OutgoingCallGraphExecutionInput graphFollowUpInput = new OutgoingCallGraphExecutionInput(1,
                Optional.of(graphTarget()));
        OutgoingCallGraphExecutionInput graphTunedInput = new OutgoingCallGraphExecutionInput(2,
                Optional.of(graphTarget()));
        DiscoverConceptsExecutionInput directConceptsInput = conceptsInput("orders");
        DiscoverEventListenersExecutionInput directListenersInput = new DiscoverEventListenersExecutionInput(
                "com.example.OrderPlaced", 0, 50);
        DiscoverConceptsExecutionInput followUpConceptsInput = new DiscoverConceptsExecutionInput(
                conceptsInput("payments").terms(), conceptsInput("payments").kinds(), Optional.empty(), 0, 25);
        IssuedCandidate graphFollowUp = followUpCandidate("candidate-graph-follow-up", binding, repositoryId, revision,
                graphPolicy, payloadCodec.encode(graphFollowUpInput));
        IssuedCandidate directRepository = repositoryCandidate("candidate-repository", binding, repositoryId);
        IssuedCandidate conceptsFollowUp = followUpCandidate("candidate-concepts-follow-up", binding, repositoryId, revision,
                conceptsPolicy, payloadCodec.encode(followUpConceptsInput));
        CapabilityExecutionResult expected = new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        when(adapter.outgoingCallGraph(any(CapabilityExecutionContext.class), eq(graphTunedInput))).thenReturn(expected);
        when(adapter.discoverConcepts(any(CapabilityExecutionContext.class), eq(directConceptsInput))).thenReturn(expected);
        when(adapter.discoverConcepts(any(CapabilityExecutionContext.class), eq(followUpConceptsInput))).thenReturn(expected);
        when(adapter.discoverEventListeners(any(CapabilityExecutionContext.class), eq(directListenersInput)))
                .thenReturn(expected);

        QueryAction graphAction = queryAction(registry.interpretToolCall(graphPolicy.name(), """
                {"candidateHandles":["candidate-graph-follow-up"],"questionToResolve":"Trace order calls","rationale":"Inspect downstream calls","depth":2}
                """, promptContext(graphPolicy, graphFollowUp, binding)));
        QueryAction directConceptsAction = queryAction(registry.interpretToolCall(conceptsPolicy.name(), """
                {"candidateHandles":["candidate-repository"],"questionToResolve":"Find order concepts","rationale":"Locate order types","searchCriteria":{"terms":[{"value":"orders","matchMode":"TOKEN_EXACT"}],"kinds":["TYPE"]}}
                """, promptContext(conceptsPolicy, directRepository, binding)));
        QueryAction followUpConceptsAction = queryAction(registry.interpretToolCall(conceptsPolicy.name(), """
                {"candidateHandles":["candidate-concepts-follow-up"],"questionToResolve":"Find payment concepts","rationale":"Continue the provider result","limit":25}
                """, promptContext(conceptsPolicy, conceptsFollowUp, binding)));
        QueryAction directListenersAction = queryAction(registry.interpretToolCall(listenersPolicy.name(), """
                {"candidateHandles":["candidate-repository"],"questionToResolve":"Find listeners","rationale":"Locate order event consumers","eventType":"com.example.OrderPlaced"}
                """, promptContext(listenersPolicy, directRepository, binding)));

        assertThat(graphAction.payload()).isEqualTo(payloadCodec.encode(graphTunedInput));
        assertThat(graphAction.candidates()).extracting(candidate -> candidate.value())
                .containsExactly("candidate-graph-follow-up");
        assertThat(graphAction.questionToResolve()).isEqualTo("Trace order calls");
        assertThat(graphAction.rationale()).isEqualTo("Inspect downstream calls");
        assertThat(directConceptsAction.payload()).isEqualTo(payloadCodec.encode(directConceptsInput));
        assertThat(followUpConceptsAction.payload()).isEqualTo(payloadCodec.encode(followUpConceptsInput));
        assertThat(directListenersAction.payload()).isEqualTo(payloadCodec.encode(directListenersInput));
        assertThat(execute(registry, graphPolicy, graphFollowUp, graphAction, revisions)).isSameAs(expected);
        assertThat(execute(registry, conceptsPolicy, directRepository, directConceptsAction, revisions)).isSameAs(expected);
        assertThat(execute(registry, conceptsPolicy, conceptsFollowUp, followUpConceptsAction, revisions)).isSameAs(expected);
        assertThat(execute(registry, listenersPolicy, directRepository, directListenersAction, revisions)).isSameAs(expected);
        verify(adapter, times(1)).outgoingCallGraph(any(CapabilityExecutionContext.class), eq(graphTunedInput));
        verify(adapter, times(1)).discoverConcepts(any(CapabilityExecutionContext.class), eq(directConceptsInput));
        verify(adapter, times(1)).discoverConcepts(any(CapabilityExecutionContext.class), eq(followUpConceptsInput));
        verify(adapter, times(1)).discoverEventListeners(any(CapabilityExecutionContext.class), eq(directListenersInput));
    }

    @Test
    void rejectsMismatchedOrStaleFollowUpsDuringNormalPlanning() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding currentBinding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                revisions);
        HandleBinding staleBinding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-0"),
                revisions);
        CapabilityPolicy graphPolicy = policy(registry, CodeIntelligenceQuery.OUTGOING_CALL_GRAPH);
        CapabilityPolicy incomingPolicy = policy(registry, CodeIntelligenceQuery.INCOMING_CALL_GRAPH);
        CapabilityInputPayload payload = payloadCodec.encode(new OutgoingCallGraphExecutionInput(1,
                Optional.of(graphTarget())));
        IssuedCandidate mismatched = followUpCandidate("candidate-mismatched", currentBinding, repositoryId, revision,
                incomingPolicy, payload);
        IssuedCandidate stale = followUpCandidate("candidate-stale", staleBinding, repositoryId, revision, graphPolicy,
                payload);

        assertThat(registry.interpretToolCall(graphPolicy.name(), """
                {"candidateHandles":["candidate-mismatched"],"questionToResolve":"Trace calls","rationale":"Continue graph analysis"}
                """, promptContext(graphPolicy, mismatched, currentBinding)))
                .isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE: "
                        + "requestedTool=codebase_outgoing_call_graph; toolStatus=NOT_CURRENTLY_ISSUED; "
                        + "expected=currentlyIssuedTool"));
        assertThat(registry.interpretToolCall(graphPolicy.name(), """
                {"candidateHandles":["candidate-stale"],"questionToResolve":"Trace calls","rationale":"Continue graph analysis"}
                """, promptContext(graphPolicy, stale, currentBinding)))
                .isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE: "
                        + "requestedTool=codebase_outgoing_call_graph; toolStatus=NOT_CURRENTLY_ISSUED; "
                        + "expected=currentlyIssuedTool"));
    }

    @Test
    void rejectsProtectedContinuationMutationsWithoutExecutingTheSemanticAdapter() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
        CapabilityPolicy conceptsPolicy = policy(registry, CodeIntelligenceQuery.DISCOVER_CONCEPTS);
        CapabilityPolicy listenersPolicy = policy(registry, CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS);
        IssuedCandidate concepts = followUpCandidate("candidate-concepts", binding, repositoryId, revision, conceptsPolicy,
                payloadCodec.encode(conceptsInput("payments")));
        DiscoverEventListenersExecutionInput listenersInput = new DiscoverEventListenersExecutionInput(
                "com.example.PaymentCreated", 5, 1);
        IssuedCandidate listeners = followUpCandidate("candidate-listeners", binding, repositoryId, revision, listenersPolicy,
                payloadCodec.encode(listenersInput));

        assertThat(registry.interpretToolCall(conceptsPolicy.name(), """
                {"candidateHandles":["candidate-concepts"],"questionToResolve":"Find concepts","rationale":"Continue",\
                "searchCriteria":{"terms":[{"value":"secrettarget","matchMode":"TOKEN_EXACT"}],"kinds":["TYPE"]}}
                """, promptContext(conceptsPolicy, concepts, binding)))
                .isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT: tool=codebase_discover_concepts; "
                        + "reason=CANDIDATE_INPUT; invalidFields=[searchCriteria]; "
                        + "constraints=[searchCriteria:ContinuationCriteria]"));
        assertThat(registry.interpretToolCall(listenersPolicy.name(), """
                {"candidateHandles":["candidate-listeners"],"questionToResolve":"Find listeners","rationale":"Continue",\
                "eventType":"secret.event.Type"}
                """, promptContext(listenersPolicy, listeners, binding)))
                .isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT: tool=codebase_discover_event_listeners; "
                        + "reason=CANDIDATE_INPUT; invalidFields=[eventType]; "
                        + "constraints=[eventType:ContinuationEventType]"));
        verify(adapter, times(0)).discoverConcepts(any(CapabilityExecutionContext.class), any());
        verify(adapter, times(0)).discoverEventListeners(any(CapabilityExecutionContext.class), any());
    }

    @Test
    void issuesInternalReferencesOnlyForAMatchingProviderFollowUp() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                new CodeIntelligencePlanningToolProvider(adapter, payloadCodec)),
                new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);
        CapabilityPolicy policy = policy(registry, CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES);
        FindInternalReferencesExecutionInput providerInput = new FindInternalReferencesExecutionInput(
                new SemanticDtos.InternalReferenceFollowUpTarget("METHOD", graphTarget()), 5, 1);
        IssuedCandidate matching = followUpCandidate("candidate-references", binding, repositoryId, revision, policy,
                payloadCodec.encode(providerInput));
        IssuedCandidate repository = repositoryCandidate("candidate-repository", binding, repositoryId);
        IssuedCandidate semantic = semanticTargetCandidate("candidate-semantic", binding, repositoryId, revision);
        PlanningToolRegistration<?> registration = registry.registrations().stream()
                .filter(candidate -> candidate.name().equals(policy.name())).findFirst().orElseThrow();

        assertThat(registration.descriptor().category()).isEqualTo(PlanningToolCategory.FOLLOW_UP_QUERY);
        assertThat(registry.issuedRegistrations(promptContext(policy, repository, binding)))
                .extracting(PlanningToolRegistration::name).doesNotContain(policy.name());
        assertThat(registry.issuedRegistrations(promptContext(policy, semantic, binding)))
                .extracting(PlanningToolRegistration::name).doesNotContain(policy.name());
        assertThat(registry.issuedRegistrations(promptContext(policy, matching, binding)))
                .extracting(PlanningToolRegistration::name).contains(policy.name());
        QueryAction action = queryAction(registry.interpretToolCall(policy.name(), """
                {"candidateHandles":["candidate-references"],"questionToResolve":"Find references","rationale":"Continue provider result","limit":25}
                """, promptContext(policy, matching, binding)));
        assertThat(payloadCodec.decode(action.payload(), FindInternalReferencesExecutionInput.class))
                .isEqualTo(new FindInternalReferencesExecutionInput(providerInput.target(), providerInput.offset(), 25));
    }

    private static CapabilityExecutionResult execute(PlanningToolRegistry registry, CapabilityPolicy policy,
                                                     IssuedCandidate candidate, QueryAction action,
                                                     RevisionVector revisions) {
        return registry.execute(new CapabilityInvocation(policy, List.of(candidate), action.questionToResolve(),
                action.payload(), revisions));
    }

    private static QueryAction queryAction(AgentActionProposal proposal) {
        return (QueryAction) ((AgentActionProposal.Proposed) proposal).action();
    }

    private static void assertInvalidTypeMemberFilter(AgentActionProposal proposal) {
        assertThat(proposal).isInstanceOfSatisfying(AgentActionProposal.Malformed.class, malformed ->
                assertThat(malformed.description()).startsWith("INVALID_TOOL_INPUT")
                        .doesNotContain("FIELD", "find", "candidate"));
    }

    private static CapabilityPolicy policy(PlanningToolRegistry registry, CodeIntelligenceQuery query) {
        return registry.availableCapabilities().stream()
                .filter(candidate -> candidate.name().equals(query.capabilityName()))
                .findFirst().orElseThrow();
    }

    private static IssuedCandidate followUpCandidate(String handleValue, HandleBinding binding,
                                                     RepositoryId repositoryId, RepositoryRevision revision,
                                                     CapabilityPolicy policy, CapabilityInputPayload payload) {
        FollowUpCandidate followUp = new FollowUpCandidate(repositoryId, revision, policy.name(), policy.version(), payload,
                "Continue semantic discovery");
        CandidateHandle handle = new CandidateHandle(handleValue, binding, CandidateKind.FOLLOW_UP);
        return new IssuedCandidate(handle, followUp);
    }

    private static IssuedCandidate repositoryCandidate(String handleValue, HandleBinding binding,
                                                       RepositoryId repositoryId) {
        CandidateHandle handle = new CandidateHandle(handleValue, binding, CandidateKind.REPOSITORY);
        return new IssuedCandidate(handle, new RepositoryCandidate(repositoryId, "Orders"));
    }

    private static IssuedCandidate semanticTargetCandidate(String handleValue, HandleBinding binding,
                                                           RepositoryId repositoryId, RepositoryRevision revision) {
        SemanticDtos.MethodTargetPayload target = graphTarget();
        SemanticTargetCandidate candidate = new SemanticTargetCandidate(repositoryId, revision,
                new JavaSemanticResultMapper().semanticTarget(new SemanticDtos.MethodTarget(target.sourceType().sourceFile(),
                        target.sourceType().javaType().packageName(), target.sourceType().javaType().className(),
                        target.methodName(), target.parameterTypes())), "Order service method");
        return new IssuedCandidate(new CandidateHandle(handleValue, binding, CandidateKind.SEMANTIC_TARGET), candidate);
    }

    private static IssuedCandidate sourceRangeCandidate(String handleValue, HandleBinding binding,
                                                        RepositoryId repositoryId, RepositoryRevision revision,
                                                        SemanticDtos.SourceRangePayload location) {
        SemanticTargetCandidate candidate = new SemanticTargetCandidate(repositoryId, revision,
                new JavaSemanticCandidateTargetMapper().semanticTarget(location), "Selected source range");
        return new IssuedCandidate(new CandidateHandle(handleValue, binding, CandidateKind.SEMANTIC_TARGET), candidate);
    }

    private static AgentPromptContext promptContext(CapabilityPolicy policy, IssuedCandidate candidate,
                                                    HandleBinding binding) {
        CapabilityHandle capability = new CapabilityHandle("capability-" + policy.name(), binding);
        return new AgentPromptContext("Find order behavior", SessionHistory.empty(), binding.runId(), binding.attemptId(),
                Map.of(capability, policy), Map.of(candidate.handle(), candidate), Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static SemanticDtos.MethodTargetPayload graphTarget() {
        return new SemanticDtos.MethodTargetPayload(new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload("com.example", "OrderService"), "src/OrderService.java"),
                "find", List.of("java.lang.String"));
    }

    private static DiscoverConceptsExecutionInput conceptsInput(String term) {
        return new DiscoverConceptsExecutionInput(List.of(new DiscoverConceptsExecutionInput.Term(term, "TOKEN_EXACT")),
                List.of("TYPE"), Optional.empty(), 0, 50);
    }
}
