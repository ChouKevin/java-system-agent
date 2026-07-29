package com.java.system.agent.answering.domain.capability;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.answering.domain.candidate.CandidateKind;

import java.util.LinkedHashSet;
import java.util.List;
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

    @JsonCreator
    public CapabilityPolicy(
            @JsonProperty("name") String name,
            @JsonProperty("version") String version,
            @JsonProperty("accepted_candidate_kinds") List<CandidateKind> acceptedCandidateKinds,
            @JsonProperty("minimum_candidates") int minimumCandidates,
            @JsonProperty("maximum_candidates") int maximumCandidates) {
        this(name, version, uniqueCandidateKinds(acceptedCandidateKinds), minimumCandidates, maximumCandidates);
    }

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

    private static Set<CandidateKind> uniqueCandidateKinds(List<CandidateKind> candidateKinds) {
        Objects.requireNonNull(candidateKinds, "accepted candidate kinds must not be null");
        Set<CandidateKind> uniqueKinds = new LinkedHashSet<>();
        for (CandidateKind candidateKind : candidateKinds) {
            if (!uniqueKinds.add(Objects.requireNonNull(candidateKind, "accepted candidate kind must not be null"))) {
                throw new IllegalArgumentException("accepted candidate kinds must not contain duplicates");
            }
        }
        return uniqueKinds;
    }
}
