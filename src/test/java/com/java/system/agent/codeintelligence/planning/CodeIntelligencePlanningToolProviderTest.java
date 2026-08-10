package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
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
import com.java.system.agent.capability.planning.QueryCapabilityRegistration;
import com.java.system.agent.capability.planning.QueryPlanningToolRegistration;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.codeintelligence.CodeIntelligenceQuery;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
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
                        CodeIntelligenceQuery.GET_METHOD_SOURCE.capabilityName(),
                        CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL.capabilityName());
        assertThat(provider.registrations())
                .filteredOn(registration -> registration instanceof FollowUpOnlyQueryRegistration<?>)
                .extracting(registration -> registration.name())
                .containsExactly(
                        CodeIntelligenceQuery.RESOLVE_CONCEPT.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName(),
                        CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES.capabilityName(),
                        CodeIntelligenceQuery.GET_EVIDENCE_SOURCE.capabilityName(),
                        CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName());

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
                Map.entry(CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName(), Set.of(CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName(), Set.of(CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES.capabilityName(), Set.of(CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.GET_EVIDENCE_SOURCE.capabilityName(), Set.of(CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.GET_METHOD_SOURCE.capabilityName(), Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                Map.entry(CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName(), Set.of(CandidateKind.FOLLOW_UP)),
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
    void describesTheCandidateKindsAcceptedByDirectQueryTools() {
        CodeIntelligencePlanningToolProvider provider = new CodeIntelligencePlanningToolProvider(
                mock(JavaSemanticServiceHttpAdapter.class), new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator()));

        assertThat(provider.registrations())
                .filteredOn(registration -> registration.name().equals(
                        CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName()))
                .singleElement()
                .extracting(registration -> registration.description())
                .isEqualTo("Agent QUERY capability. candidateHandles must contain exactly 1 candidate of kinds "
                        + "[FOLLOW_UP, REPOSITORY]. A FOLLOW_UP candidate must target "
                        + "codebase_discover_concepts@v1.");
    }

    @Test
    void exposesMethodImplementationDiscoveryOnlyThroughProviderIssuedFollowUp() {
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
        AgentPromptContext context = promptContext(policy, followUp, binding);
        CapabilityExecutionResult expected = new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        when(adapter.discoverMethodImplementations(any(CapabilityExecutionContext.class), eq(input)))
                .thenReturn(expected);

        assertThat(registry.issuedRegistrations(context))
                .extracting(registration -> registration.name())
                .contains(policy.name())
                .doesNotContain("codebase_follow_up");

        QueryAction action = queryAction(registry.interpretToolCall(policy.name(), """
                {"followUpCandidateHandle":"candidate-implementations","questionToResolve":"Find implementations","rationale":"Provider authorized this declaration"}
                """, context));
        assertThat(action.capability()).isEqualTo(context.issuedCapabilities().keySet().iterator().next());
        assertThat(action.candidates()).extracting(candidate -> candidate.value())
                .containsExactly("candidate-implementations");
        assertThat(action.payload()).isEqualTo(payloadCodec.encode(input));
        assertThat(execute(registry, policy, followUp, action, revisions)).isSameAs(expected);
        verify(adapter, times(1)).discoverMethodImplementations(any(CapabilityExecutionContext.class), eq(input));
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
    void bindsFollowUpPayloadsDuringNormalPlanningWhileDirectInputsRemainMapperProduced() {
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
        OutgoingCallGraphExecutionInput graphFollowUpInput = new OutgoingCallGraphExecutionInput(1,
                Optional.of(graphTarget()));
        DiscoverConceptsExecutionInput directConceptsInput = conceptsInput("orders");
        DiscoverConceptsExecutionInput followUpConceptsInput = conceptsInput("payments");
        IssuedCandidate graphFollowUp = followUpCandidate("candidate-graph-follow-up", binding, repositoryId, revision,
                graphPolicy, payloadCodec.encode(graphFollowUpInput));
        IssuedCandidate directRepository = repositoryCandidate("candidate-repository", binding, repositoryId);
        IssuedCandidate conceptsFollowUp = followUpCandidate("candidate-concepts-follow-up", binding, repositoryId, revision,
                conceptsPolicy, payloadCodec.encode(followUpConceptsInput));
        CapabilityExecutionResult expected = new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        when(adapter.outgoingCallGraph(any(CapabilityExecutionContext.class), eq(graphFollowUpInput))).thenReturn(expected);
        when(adapter.discoverConcepts(any(CapabilityExecutionContext.class), eq(directConceptsInput))).thenReturn(expected);
        when(adapter.discoverConcepts(any(CapabilityExecutionContext.class), eq(followUpConceptsInput))).thenReturn(expected);

        QueryAction graphAction = queryAction(registry.interpretToolCall(graphPolicy.name(), """
                {"candidateHandles":["candidate-graph-follow-up"],"questionToResolve":"Trace order calls","rationale":"Inspect downstream calls","depth":2}
                """, promptContext(graphPolicy, graphFollowUp, binding)));
        QueryAction directConceptsAction = queryAction(registry.interpretToolCall(conceptsPolicy.name(), """
                {"candidateHandles":["candidate-repository"],"questionToResolve":"Find order concepts","rationale":"Locate order types","terms":[{"value":"orders","matchMode":"TOKEN_EXACT"}],"kinds":["TYPE"]}
                """, promptContext(conceptsPolicy, directRepository, binding)));
        QueryAction followUpConceptsAction = queryAction(registry.interpretToolCall(conceptsPolicy.name(), """
                {"candidateHandles":["candidate-concepts-follow-up"],"questionToResolve":"Find payment concepts","rationale":"Continue the provider result","terms":[{"value":"orders","matchMode":"TOKEN_EXACT"}],"kinds":["TYPE"]}
                """, promptContext(conceptsPolicy, conceptsFollowUp, binding)));

        assertThat(graphAction.payload()).isEqualTo(payloadCodec.encode(graphFollowUpInput));
        assertThat(graphAction.candidates()).extracting(candidate -> candidate.value())
                .containsExactly("candidate-graph-follow-up");
        assertThat(graphAction.questionToResolve()).isEqualTo("Trace order calls");
        assertThat(graphAction.rationale()).isEqualTo("Inspect downstream calls");
        assertThat(directConceptsAction.payload()).isEqualTo(payloadCodec.encode(directConceptsInput));
        assertThat(followUpConceptsAction.payload()).isEqualTo(payloadCodec.encode(followUpConceptsInput));
        assertThat(execute(registry, graphPolicy, graphFollowUp, graphAction, revisions)).isSameAs(expected);
        assertThat(execute(registry, conceptsPolicy, directRepository, directConceptsAction, revisions)).isSameAs(expected);
        assertThat(execute(registry, conceptsPolicy, conceptsFollowUp, followUpConceptsAction, revisions)).isSameAs(expected);
        verify(adapter, times(1)).outgoingCallGraph(any(CapabilityExecutionContext.class), eq(graphFollowUpInput));
        verify(adapter, times(1)).discoverConcepts(any(CapabilityExecutionContext.class), eq(directConceptsInput));
        verify(adapter, times(1)).discoverConcepts(any(CapabilityExecutionContext.class), eq(followUpConceptsInput));
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
                .isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(registry.interpretToolCall(graphPolicy.name(), """
                {"candidateHandles":["candidate-stale"],"questionToResolve":"Trace calls","rationale":"Continue graph analysis"}
                """, promptContext(graphPolicy, stale, currentBinding)))
                .isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
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
