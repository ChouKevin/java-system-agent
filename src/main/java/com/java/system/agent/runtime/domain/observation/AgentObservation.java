package com.java.system.agent.runtime.domain.observation;

import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;

import java.util.Objects;
import java.util.Set;

/**
 * Runtime 已將候選與證據綁定 handle 後的可引用觀察結果
 */
public record AgentObservation(ObservationId id, ObservationSource source, ObservationCode code, String description,
        Set<CandidateHandle> candidateHandles, Set<EvidenceHandle> evidenceHandles, String provenance) {
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
}
