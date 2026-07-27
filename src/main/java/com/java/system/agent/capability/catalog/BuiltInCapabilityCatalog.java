package com.java.system.agent.capability.catalog;

import com.java.system.agent.runtime.domain.capability.ArgumentDefinition;
import com.java.system.agent.runtime.domain.capability.ArgumentType;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.port.out.CapabilityCatalogPort;

import java.util.List;
import java.util.Set;

/**
 * 提供固定順序內建 capability descriptor 的 runtime catalog adapter
 */
public final class BuiltInCapabilityCatalog implements CapabilityCatalogPort {

    private static final List<CapabilityDescriptor> CAPABILITIES = List.of(
            descriptor(
                    "codebase.list-entry-points",
                    Set.of(CandidateKind.REPOSITORY),
                    1,
                    1,
                    new ArgumentDefinition("type", ArgumentType.ENUM, false, null, null,
                            Set.of("API", "MQ", "SCHEDULE"))),
            descriptor(
                    "codebase.lookup-api-route",
                    Set.of(CandidateKind.REPOSITORY),
                    0,
                    1,
                    new ArgumentDefinition("apiPath", ArgumentType.TEXT, true, null, null, Set.of()),
                    new ArgumentDefinition("httpMethod", ArgumentType.TEXT, false, null, null, Set.of())),
            descriptor(
                    "codebase.suggest-api-route",
                    Set.of(CandidateKind.REPOSITORY),
                    0,
                    1,
                    new ArgumentDefinition("apiPath", ArgumentType.TEXT, true, null, null, Set.of()),
                    new ArgumentDefinition("httpMethod", ArgumentType.TEXT, false, null, null, Set.of()),
                    new ArgumentDefinition("limit", ArgumentType.INTEGER, true, 1, 20, Set.of())),
            descriptor(
                    "codebase.outgoing-call-graph",
                    Set.of(CandidateKind.SEMANTIC_TARGET),
                    1,
                    1,
                    new ArgumentDefinition("depth", ArgumentType.INTEGER, false, 1, 2, Set.of())),
            descriptor(
                    "codebase.incoming-call-graph",
                    Set.of(CandidateKind.SEMANTIC_TARGET),
                    1,
                    1,
                    new ArgumentDefinition("depth", ArgumentType.INTEGER, false, 1, 2, Set.of())));

    @Override
    public List<CapabilityDescriptor> availableCapabilities() {
        return CAPABILITIES;
    }

    private static CapabilityDescriptor descriptor(
            String name,
            Set<CandidateKind> candidateKinds,
            int minimumCandidates,
            int maximumCandidates,
            ArgumentDefinition... arguments) {
        return new CapabilityDescriptor(
                name,
                "v1",
                candidateKinds,
                minimumCandidates,
                maximumCandidates,
                new CapabilityQuerySchema(List.of(arguments)));
    }
}
