package com.java.system.agent.runtime.domain.capability;

import com.java.system.agent.runtime.domain.candidate.CandidateKind;

import java.util.Objects;
import java.util.Set;

/**
 * Runtime 可配發 capability 的名稱與候選子集政策
 * 最小與最大候選數只套用於單一 QUERY 的 candidate subset，不套用於整個 reasoning loop
 */
public record CapabilityPolicy(
        String name,
        String version,
        Set<CandidateKind> acceptedCandidateKinds,
        int minimumCandidates,
        int maximumCandidates) {

    public CapabilityPolicy {
        Objects.requireNonNull(name, "capability name must not be null");
        Objects.requireNonNull(version, "capability version must not be null");
        Objects.requireNonNull(acceptedCandidateKinds, "accepted candidate kinds must not be null");
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
