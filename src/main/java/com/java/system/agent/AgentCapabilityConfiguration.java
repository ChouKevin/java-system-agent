package com.java.system.agent;

import com.java.system.agent.capability.dispatch.CapabilityExecutionDispatcher;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.codebase.executor.IncomingCallGraphExecutor;
import com.java.system.agent.codebase.executor.ListEntryPointsExecutor;
import com.java.system.agent.codebase.executor.LookupApiRouteExecutor;
import com.java.system.agent.codebase.executor.OutgoingCallGraphExecutor;
import com.java.system.agent.codebase.executor.SuggestApiRouteExecutor;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.codebase.planning.IncomingCallGraphExecutionInput;
import com.java.system.agent.codebase.planning.IncomingCallGraphPlanningInput;
import com.java.system.agent.codebase.planning.IncomingCallGraphPlanningMapper;
import com.java.system.agent.codebase.planning.ListEntryPointsExecutionInput;
import com.java.system.agent.codebase.planning.ListEntryPointsPlanningInput;
import com.java.system.agent.codebase.planning.ListEntryPointsPlanningMapper;
import com.java.system.agent.codebase.planning.LookupApiRouteExecutionInput;
import com.java.system.agent.codebase.planning.LookupApiRoutePlanningInput;
import com.java.system.agent.codebase.planning.LookupApiRoutePlanningMapper;
import com.java.system.agent.codebase.planning.OutgoingCallGraphExecutionInput;
import com.java.system.agent.codebase.planning.OutgoingCallGraphPlanningInput;
import com.java.system.agent.codebase.planning.OutgoingCallGraphPlanningMapper;
import com.java.system.agent.codebase.planning.SuggestApiRouteExecutionInput;
import com.java.system.agent.codebase.planning.SuggestApiRoutePlanningInput;
import com.java.system.agent.codebase.planning.SuggestApiRoutePlanningMapper;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;

import java.util.List;
import java.util.Set;

/**
 * 建構唯一 capability tool registry 與 execution dispatcher 的根設定
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent-runtime")
public final class AgentCapabilityConfiguration {

    @Bean
    PlanningToolRegistry planningToolRegistry(JavaSemanticServiceHttpAdapter adapter, ObjectMapper objectMapper, Validator validator) {
        CapabilityPolicy listEntryPoints = policy("codebase_list_entry_points", Set.of(CandidateKind.REPOSITORY), 1, 1);
        CapabilityPolicy lookupApiRoute = policy("codebase_lookup_api_route", Set.of(CandidateKind.REPOSITORY), 0, 1);
        CapabilityPolicy suggestApiRoute = policy("codebase_suggest_api_route", Set.of(CandidateKind.REPOSITORY), 0, 1);
        CapabilityPolicy outgoingCallGraph = policy("codebase_outgoing_call_graph", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1);
        CapabilityPolicy incomingCallGraph = policy("codebase_incoming_call_graph", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1);
        PlanningToolSchemaFactory schemaFactory = new PlanningToolSchemaFactory(objectMapper);
        return new PlanningToolRegistry(List.of(
                PlanningToolRegistry.registration(listEntryPoints, ListEntryPointsPlanningInput.class, ListEntryPointsExecutionInput.class, new ListEntryPointsPlanningMapper(), new ListEntryPointsExecutor(adapter), schemaFactory),
                PlanningToolRegistry.registration(lookupApiRoute, LookupApiRoutePlanningInput.class, LookupApiRouteExecutionInput.class, new LookupApiRoutePlanningMapper(), new LookupApiRouteExecutor(adapter), schemaFactory),
                PlanningToolRegistry.registration(suggestApiRoute, SuggestApiRoutePlanningInput.class, SuggestApiRouteExecutionInput.class, new SuggestApiRoutePlanningMapper(), new SuggestApiRouteExecutor(adapter), schemaFactory),
                PlanningToolRegistry.registration(outgoingCallGraph, OutgoingCallGraphPlanningInput.class, OutgoingCallGraphExecutionInput.class, new OutgoingCallGraphPlanningMapper(), new OutgoingCallGraphExecutor(adapter), schemaFactory),
                PlanningToolRegistry.registration(incomingCallGraph, IncomingCallGraphPlanningInput.class, IncomingCallGraphExecutionInput.class, new IncomingCallGraphPlanningMapper(), new IncomingCallGraphExecutor(adapter), schemaFactory)),
                new StrictPlanningToolDecoder(objectMapper, validator), new CanonicalCapabilityPayloadCodec(objectMapper));
    }

    @Bean
    CapabilityExecutionPort capabilityExecutionPort(PlanningToolRegistry registry) {
        return new CapabilityExecutionDispatcher(registry);
    }

    private static CapabilityPolicy policy(
            String name, Set<CandidateKind> candidateKinds, int minimumCandidates, int maximumCandidates) {
        return new CapabilityPolicy(name, "v1", candidateKinds, minimumCandidates, maximumCandidates);
    }
}
