package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
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

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 提供 Java code intelligence QUERY planning tool 的 candidate-free registration。 */
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
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.LIST_ENTRY_POINTS),
                        ListEntryPointsPlanningInput.class, ListEntryPointsExecutionInput.class,
                        new ListEntryPointsPlanningMapper(), new ListEntryPointsExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.LOOKUP_API_ROUTE),
                        LookupApiRoutePlanningInput.class, LookupApiRouteExecutionInput.class,
                        new LookupApiRoutePlanningMapper(), new LookupApiRouteExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.SUGGEST_API_ROUTE),
                        SuggestApiRoutePlanningInput.class, SuggestApiRouteExecutionInput.class,
                        new SuggestApiRoutePlanningMapper(), new SuggestApiRouteExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.OUTGOING_CALL_GRAPH),
                        OutgoingCallGraphPlanningInput.class, OutgoingCallGraphExecutionInput.class,
                        new OutgoingCallGraphPlanningMapper(), new OutgoingCallGraphExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.INCOMING_CALL_GRAPH),
                        IncomingCallGraphPlanningInput.class, IncomingCallGraphExecutionInput.class,
                        new IncomingCallGraphPlanningMapper(), new IncomingCallGraphExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.DISCOVER_CONCEPTS),
                        DiscoverConceptsPlanningInput.class, DiscoverConceptsExecutionInput.class,
                        new DiscoverConceptsPlanningMapper(), new DiscoverConceptsExecutor(requiredAdapter), requiredPayloadCodec,
                        CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName()),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.RESOLVE_CONCEPT),
                        ResolveConceptPlanningInput.class, ResolveConceptExecutionInput.class,
                        new ResolveConceptPlanningMapper(), new ResolveConceptExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS),
                        DiscoverEventListenersPlanningInput.class, DiscoverEventListenersExecutionInput.class,
                        new DiscoverEventListenersPlanningMapper(), new DiscoverEventListenersExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS),
                        DiscoverMethodImplementationsPlanningInput.class, DiscoverMethodImplementationsExecutionInput.class,
                        new DiscoverMethodImplementationsPlanningMapper(), new DiscoverMethodImplementationsExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS),
                        DiscoverTypeMembersPlanningInput.class, DiscoverTypeMembersExecutionInput.class,
                        new DiscoverTypeMembersPlanningMapper(), new DiscoverTypeMembersExecutor(requiredAdapter), requiredPayloadCodec,
                        CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName()),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES),
                        FindInternalReferencesPlanningInput.class, FindInternalReferencesExecutionInput.class,
                        new FindInternalReferencesPlanningMapper(), new FindInternalReferencesExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.GET_EVIDENCE_SOURCE),
                        GetEvidenceSourcePlanningInput.class, GetEvidenceSourceExecutionInput.class,
                        new GetEvidenceSourcePlanningMapper(), new GetEvidenceSourceExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.GET_METHOD_SOURCE),
                        GetMethodSourcePlanningInput.class, GetMethodSourceExecutionInput.class,
                        new GetMethodSourcePlanningMapper(), new GetMethodSourceExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.GET_SOURCE_SEGMENT),
                        GetSourceSegmentPlanningInput.class, GetSourceSegmentExecutionInput.class,
                        new GetSourceSegmentPlanningMapper(), new GetSourceSegmentExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(policy(CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL),
                        ResolveSourceSymbolPlanningInput.class, ResolveSourceSymbolExecutionInput.class,
                        new ResolveSourceSymbolPlanningMapper(), new ResolveSourceSymbolExecutor(requiredAdapter), requiredPayloadCodec));
    }

    @Override
    public List<PlanningToolRegistration<?>> registrations() {
        return registrations;
    }

    private static CapabilityPolicy policy(CodeIntelligenceQuery query) {
        return new CapabilityPolicy(query.capabilityName(), query.version());
    }
}
