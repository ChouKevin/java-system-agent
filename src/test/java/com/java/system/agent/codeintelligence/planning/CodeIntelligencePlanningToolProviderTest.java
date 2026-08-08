package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
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
    void registersTheM6SemanticQueryCatalogWithDirectAndBoundFollowUpContracts() {
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
                        CodeIntelligenceQuery.GET_METHOD_SOURCE.capabilityName(),
                        CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL.capabilityName());
        assertThat(provider.registrations())
                .filteredOn(registration -> registration instanceof FollowUpOnlyQueryRegistration<?>)
                .extracting(registration -> registration.name())
                .containsExactly(
                        CodeIntelligenceQuery.RESOLVE_CONCEPT.capabilityName(),
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
                Map.entry(CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName(), Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
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
}
