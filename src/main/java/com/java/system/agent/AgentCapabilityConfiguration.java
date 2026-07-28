package com.java.system.agent;

import com.java.system.agent.capability.dispatch.CapabilityExecutionDispatcher;
import com.java.system.agent.capability.tool.CapabilityToolRegistry;
import com.java.system.agent.codebase.executor.IncomingCallGraphExecutor;
import com.java.system.agent.codebase.executor.ListEntryPointsExecutor;
import com.java.system.agent.codebase.executor.LookupApiRouteExecutor;
import com.java.system.agent.codebase.executor.OutgoingCallGraphExecutor;
import com.java.system.agent.codebase.executor.SuggestApiRouteExecutor;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Set;

/**
 * 建構唯一 capability tool registry 與 execution dispatcher 的根設定
 */
@Configuration(proxyBeanMethods = false)
@Profile("agent-runtime")
public final class AgentCapabilityConfiguration {

    @Bean
    CapabilityToolRegistry capabilityToolRegistry(JavaSemanticServiceHttpAdapter adapter) {
        CapabilityPolicy listEntryPoints = policy("codebase.list-entry-points", Set.of(CandidateKind.REPOSITORY), 1, 1);
        CapabilityPolicy lookupApiRoute = policy("codebase.lookup-api-route", Set.of(CandidateKind.REPOSITORY), 0, 1);
        CapabilityPolicy suggestApiRoute = policy("codebase.suggest-api-route", Set.of(CandidateKind.REPOSITORY), 0, 1);
        CapabilityPolicy outgoingCallGraph = policy("codebase.outgoing-call-graph", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1);
        CapabilityPolicy incomingCallGraph = policy("codebase.incoming-call-graph", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1);
        return new CapabilityToolRegistry(List.of(
                CapabilityToolRegistry.registration(listEntryPoints,
                        CapabilityToolRegistry.decoder(CapabilityToolRegistry.optionalEnum("type", Set.of("API", "MQ", "SCHEDULE"))),
                        new ListEntryPointsExecutor(listEntryPoints, adapter)),
                CapabilityToolRegistry.registration(lookupApiRoute,
                        CapabilityToolRegistry.decoder(CapabilityToolRegistry.requiredText("apiPath"),
                                CapabilityToolRegistry.optionalText("httpMethod")),
                        new LookupApiRouteExecutor(lookupApiRoute, adapter)),
                CapabilityToolRegistry.registration(suggestApiRoute,
                        CapabilityToolRegistry.decoder(CapabilityToolRegistry.requiredText("apiPath"),
                                CapabilityToolRegistry.optionalText("httpMethod"),
                                CapabilityToolRegistry.requiredInteger("limit", 1, 20)),
                        new SuggestApiRouteExecutor(suggestApiRoute, adapter)),
                CapabilityToolRegistry.registration(outgoingCallGraph,
                        CapabilityToolRegistry.decoder(CapabilityToolRegistry.optionalInteger("depth", 1, 2)),
                        new OutgoingCallGraphExecutor(outgoingCallGraph, adapter)),
                CapabilityToolRegistry.registration(incomingCallGraph,
                        CapabilityToolRegistry.decoder(CapabilityToolRegistry.optionalInteger("depth", 1, 2)),
                        new IncomingCallGraphExecutor(incomingCallGraph, adapter))));
    }

    @Bean
    CapabilityExecutionPort capabilityExecutionPort(CapabilityToolRegistry registry) {
        return new CapabilityExecutionDispatcher(registry);
    }

    private static CapabilityPolicy policy(
            String name, Set<CandidateKind> candidateKinds, int minimumCandidates, int maximumCandidates) {
        return new CapabilityPolicy(name, "v1", candidateKinds, minimumCandidates, maximumCandidates);
    }
}
