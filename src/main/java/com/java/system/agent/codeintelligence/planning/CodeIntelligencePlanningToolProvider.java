package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolCategory;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.codeintelligence.CodeIntelligenceQuery;
import com.java.system.agent.codeintelligence.executor.DiscoverConceptsExecutor;
import com.java.system.agent.codeintelligence.executor.DiscoverEventListenersExecutor;
import com.java.system.agent.codeintelligence.executor.DiscoverMethodImplementationsExecutor;
import com.java.system.agent.codeintelligence.executor.DiscoverTypeMembersExecutor;
import com.java.system.agent.codeintelligence.executor.FindInternalReferencesExecutor;
import com.java.system.agent.codeintelligence.executor.GetEvidenceSourceExecutor;
import com.java.system.agent.codeintelligence.executor.GetMethodSourceExecutor;
import com.java.system.agent.codeintelligence.executor.GetSourceSegmentExecutor;
import com.java.system.agent.codeintelligence.executor.IncomingCallGraphExecutor;
import com.java.system.agent.codeintelligence.executor.ListEntryPointsExecutor;
import com.java.system.agent.codeintelligence.executor.LookupApiRouteExecutor;
import com.java.system.agent.codeintelligence.executor.OutgoingCallGraphExecutor;
import com.java.system.agent.codeintelligence.executor.ResolveConceptExecutor;
import com.java.system.agent.codeintelligence.executor.ResolveSourceSymbolExecutor;
import com.java.system.agent.codeintelligence.executor.SuggestApiRouteExecutor;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 提供 Java code intelligence QUERY planning tool 的 registration
 */
public final class CodeIntelligencePlanningToolProvider implements PlanningToolProvider {

    private final List<PlanningToolRegistration<?>> registrations;

    public CodeIntelligencePlanningToolProvider(
            JavaSemanticServiceHttpAdapter adapter,
            CanonicalCapabilityPayloadCodec payloadCodec) {
        JavaSemanticServiceHttpAdapter requiredAdapter = Objects.requireNonNull(
                adapter, "Java semantic service adapter must not be null");
        CanonicalCapabilityPayloadCodec requiredPayloadCodec = Objects.requireNonNull(
                payloadCodec, "capability payload codec must not be null");
        this.registrations = List.of(
                PlanningToolRegistry.registration(
                        policy(CodeIntelligenceQuery.LIST_ENTRY_POINTS, Set.of(CandidateKind.REPOSITORY)),
                        ListEntryPointsPlanningInput.class, ListEntryPointsExecutionInput.class,
                        new ListEntryPointsPlanningMapper(), new ListEntryPointsExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(
                        policy(CodeIntelligenceQuery.LOOKUP_API_ROUTE, Set.of(CandidateKind.REPOSITORY)),
                        LookupApiRoutePlanningInput.class, LookupApiRouteExecutionInput.class,
                        new LookupApiRoutePlanningMapper(), new LookupApiRouteExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(
                        policy(CodeIntelligenceQuery.SUGGEST_API_ROUTE, Set.of(CandidateKind.REPOSITORY)),
                        SuggestApiRoutePlanningInput.class, SuggestApiRouteExecutionInput.class,
                        new SuggestApiRoutePlanningMapper(), new SuggestApiRouteExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.candidateBoundRegistration(
                        PlanningToolCategory.QUERY,
                        policy(CodeIntelligenceQuery.OUTGOING_CALL_GRAPH,
                                Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                        OutgoingCallGraphPlanningInput.class, OutgoingCallGraphExecutionInput.class,
                        CodeIntelligenceCandidateExecutionPlanners.outgoingCallGraph(),
                        new OutgoingCallGraphExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.candidateBoundRegistration(
                        PlanningToolCategory.QUERY,
                        policy(CodeIntelligenceQuery.INCOMING_CALL_GRAPH,
                                Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                        IncomingCallGraphPlanningInput.class, IncomingCallGraphExecutionInput.class,
                        CodeIntelligenceCandidateExecutionPlanners.incomingCallGraph(),
                        new IncomingCallGraphExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.candidateBoundRegistration(
                        PlanningToolCategory.QUERY,
                        policy(CodeIntelligenceQuery.DISCOVER_CONCEPTS,
                                Set.of(CandidateKind.REPOSITORY, CandidateKind.FOLLOW_UP)),
                        DiscoverConceptsPlanningInput.class, DiscoverConceptsExecutionInput.class,
                        CodeIntelligenceCandidateExecutionPlanners.discoverConcepts(),
                        new DiscoverConceptsExecutor(requiredAdapter), requiredPayloadCodec,
                        CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName()),
                PlanningToolRegistry.followUpOnlyRegistration(
                        policy(CodeIntelligenceQuery.RESOLVE_CONCEPT, Set.of(CandidateKind.FOLLOW_UP)),
                        ResolveConceptExecutionInput.class, new ResolveConceptExecutor(requiredAdapter)),
                PlanningToolRegistry.candidateBoundRegistration(
                        PlanningToolCategory.QUERY,
                        policy(CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS,
                                Set.of(CandidateKind.REPOSITORY, CandidateKind.FOLLOW_UP)),
                        DiscoverEventListenersPlanningInput.class, DiscoverEventListenersExecutionInput.class,
                        CodeIntelligenceCandidateExecutionPlanners.discoverEventListeners(),
                        new DiscoverEventListenersExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.candidateBoundRegistration(
                        PlanningToolCategory.QUERY,
                        policy(CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS,
                                Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                        DiscoverMethodImplementationsPlanningInput.class,
                        DiscoverMethodImplementationsExecutionInput.class,
                        CodeIntelligenceCandidateExecutionPlanners.discoverMethodImplementations(),
                        new DiscoverMethodImplementationsExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.candidateBoundRegistration(
                        PlanningToolCategory.QUERY,
                        policy(CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS,
                                Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                        DiscoverTypeMembersPlanningInput.class, DiscoverTypeMembersExecutionInput.class,
                        CodeIntelligenceCandidateExecutionPlanners.discoverTypeMembers(),
                        new DiscoverTypeMembersExecutor(requiredAdapter), requiredPayloadCodec,
                        CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName()),
                PlanningToolRegistry.candidateBoundRegistration(
                        PlanningToolCategory.FOLLOW_UP_QUERY,
                        policy(CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES, Set.of(CandidateKind.FOLLOW_UP)),
                        FindInternalReferencesPlanningInput.class, FindInternalReferencesExecutionInput.class,
                        CodeIntelligenceCandidateExecutionPlanners.findInternalReferences(),
                        new FindInternalReferencesExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.followUpOnlyRegistration(
                        policy(CodeIntelligenceQuery.GET_EVIDENCE_SOURCE, Set.of(CandidateKind.FOLLOW_UP)),
                        GetEvidenceSourceExecutionInput.class, new GetEvidenceSourceExecutor(requiredAdapter)),
                PlanningToolRegistry.candidateBoundRegistration(
                        PlanningToolCategory.QUERY,
                        policy(CodeIntelligenceQuery.GET_METHOD_SOURCE,
                                Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                        GetMethodSourcePlanningInput.class, GetMethodSourceExecutionInput.class,
                        CodeIntelligenceCandidateExecutionPlanners.getMethodSource(),
                        new GetMethodSourceExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.candidateBoundRegistration(
                        PlanningToolCategory.QUERY,
                        policy(CodeIntelligenceQuery.GET_SOURCE_SEGMENT,
                                Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                        GetSourceSegmentPlanningInput.class, GetSourceSegmentExecutionInput.class,
                        CodeIntelligenceCandidateExecutionPlanners.getSourceSegment(),
                        new GetSourceSegmentExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.candidateBoundRegistration(
                        PlanningToolCategory.QUERY,
                        policy(CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL,
                                Set.of(CandidateKind.SEMANTIC_TARGET, CandidateKind.FOLLOW_UP)),
                        ResolveSourceSymbolPlanningInput.class, ResolveSourceSymbolExecutionInput.class,
                        CodeIntelligenceCandidateExecutionPlanners.resolveSourceSymbol(),
                        new ResolveSourceSymbolExecutor(requiredAdapter), requiredPayloadCodec));
    }

    @Override
    public List<PlanningToolRegistration<?>> registrations() {
        return registrations;
    }

    private static CapabilityPolicy policy(CodeIntelligenceQuery query, Set<CandidateKind> candidateKinds) {
        return new CapabilityPolicy(query.capabilityName(), query.version(), candidateKinds, 1, 1);
    }
}
