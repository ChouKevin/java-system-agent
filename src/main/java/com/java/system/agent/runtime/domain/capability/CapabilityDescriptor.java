package com.java.system.agent.runtime.domain.capability;

import com.java.system.agent.runtime.domain.candidate.CandidateKind;

import java.util.Objects;
import java.util.Set;

/**
 * Runtime 可配發 capability 的名稱、候選限制與查詢契約
 */
public record CapabilityDescriptor(
        String name,
        String version,
        Set<CandidateKind> acceptedCandidateKinds,
        int minimumCandidates,
        int maximumCandidates,
        CapabilityQuerySchema querySchema) {

    public CapabilityDescriptor {
        Objects.requireNonNull(name, "capability name must not be null");
        Objects.requireNonNull(version, "capability version must not be null");
        Objects.requireNonNull(acceptedCandidateKinds, "accepted candidate kinds must not be null");
        Objects.requireNonNull(querySchema, "capability query schema must not be null");
        name = name.trim();
        version = version.trim();
        if (name.isBlank() || version.isBlank()) {
            throw new IllegalArgumentException("capability name and version must not be blank");
        }
        acceptedCandidateKinds = Set.copyOf(acceptedCandidateKinds);
        if (acceptedCandidateKinds.isEmpty()) {
            throw new IllegalArgumentException("capability must accept at least one candidate kind");
        }
        if (minimumCandidates < 0 || maximumCandidates < minimumCandidates) {
            throw new IllegalArgumentException("capability candidate cardinality is incoherent");
        }
    }
}
