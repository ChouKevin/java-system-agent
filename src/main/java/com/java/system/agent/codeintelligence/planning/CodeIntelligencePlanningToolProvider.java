package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.PlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.codeintelligence.executor.IncomingCallGraphExecutor;
import com.java.system.agent.codeintelligence.executor.ListEntryPointsExecutor;
import com.java.system.agent.codeintelligence.executor.LookupApiRouteExecutor;
import com.java.system.agent.codeintelligence.executor.OutgoingCallGraphExecutor;
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
                        policy("codebase_list_entry_points", Set.of(CandidateKind.REPOSITORY), 1, 1),
                        ListEntryPointsPlanningInput.class, ListEntryPointsExecutionInput.class,
                        new ListEntryPointsPlanningMapper(), new ListEntryPointsExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(
                        policy("codebase_lookup_api_route", Set.of(CandidateKind.REPOSITORY), 0, 1),
                        LookupApiRoutePlanningInput.class, LookupApiRouteExecutionInput.class,
                        new LookupApiRoutePlanningMapper(), new LookupApiRouteExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(
                        policy("codebase_suggest_api_route", Set.of(CandidateKind.REPOSITORY), 0, 1),
                        SuggestApiRoutePlanningInput.class, SuggestApiRouteExecutionInput.class,
                        new SuggestApiRoutePlanningMapper(), new SuggestApiRouteExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(
                        policy("codebase_outgoing_call_graph", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1),
                        OutgoingCallGraphPlanningInput.class, OutgoingCallGraphExecutionInput.class,
                        new OutgoingCallGraphPlanningMapper(), new OutgoingCallGraphExecutor(requiredAdapter), requiredPayloadCodec),
                PlanningToolRegistry.registration(
                        policy("codebase_incoming_call_graph", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1),
                        IncomingCallGraphPlanningInput.class, IncomingCallGraphExecutionInput.class,
                        new IncomingCallGraphPlanningMapper(), new IncomingCallGraphExecutor(requiredAdapter), requiredPayloadCodec));
    }

    @Override
    public List<PlanningToolRegistration<?>> registrations() {
        return registrations;
    }

    private static CapabilityPolicy policy(
            String name, Set<CandidateKind> candidateKinds, int minimumCandidates, int maximumCandidates) {
        return new CapabilityPolicy(name, "v1", candidateKinds, minimumCandidates, maximumCandidates);
    }
}
