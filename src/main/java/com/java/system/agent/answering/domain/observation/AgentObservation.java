package com.java.system.agent.answering.domain.observation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime 已將候選與證據綁定 handle 後的可引用觀察結果
 */
public record AgentObservation(ObservationId id, ObservationSource source, ObservationCode code, String description,
        Set<CandidateHandle> candidateHandles, Set<EvidenceHandle> evidenceHandles, String provenance) {

    @JsonCreator
    public AgentObservation(
            @JsonProperty("id") ObservationId id,
            @JsonProperty("source") ObservationSource source,
            @JsonProperty("code") ObservationCode code,
            @JsonProperty("description") String description,
            @JsonProperty("candidate_handles") List<CandidateHandle> candidateHandles,
            @JsonProperty("evidence_handles") List<EvidenceHandle> evidenceHandles,
            @JsonProperty("provenance") String provenance) {
        this(id, source, code, description,
                uniqueValues(candidateHandles, "agent observation candidate handles"),
                uniqueValues(evidenceHandles, "agent observation evidence handles"),
                provenance);
    }

    public AgentObservation {
        Objects.requireNonNull(id, "agent observation ID must not be null");
        Objects.requireNonNull(source, "agent observation source must not be null");
        Objects.requireNonNull(code, "agent observation code must not be null");
        Objects.requireNonNull(description, "agent observation description must not be null");
        Objects.requireNonNull(candidateHandles, "agent observation candidate handles must not be null");
        Objects.requireNonNull(evidenceHandles, "agent observation evidence handles must not be null");
        Objects.requireNonNull(provenance, "agent observation provenance must not be null");
        if (description.isBlank() || provenance.isBlank()) throw new IllegalArgumentException("agent observation description and provenance must not be blank");
        candidateHandles = Set.copyOf(candidateHandles); evidenceHandles = Set.copyOf(evidenceHandles);
    }

    private static <T> Set<T> uniqueValues(List<T> values, String description) {
        Objects.requireNonNull(values, description + " must not be null");
        Set<T> uniqueValues = new LinkedHashSet<>();
        for (T value : values) {
            if (!uniqueValues.add(Objects.requireNonNull(value, description + " must not contain null"))) {
                throw new IllegalArgumentException(description + " must not contain duplicates");
            }
        }
        return uniqueValues;
    }
}
