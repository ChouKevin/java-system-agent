package com.java.system.agent.answering.domain.observation;

import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;

import java.util.List;
import java.util.Objects;

/**
 * capability 執行器原始回傳的觀察結果，尚未配發 answering handle
 */
public record CapabilityObservation(ObservationCode code, String description, List<AnalysisCandidate> candidates,
        List<EvidenceRef> evidence, String provenance) {
    public CapabilityObservation {
        Objects.requireNonNull(code, "capability observation code must not be null");
        Objects.requireNonNull(description, "capability observation description must not be null");
        Objects.requireNonNull(candidates, "capability observation candidates must not be null");
        Objects.requireNonNull(evidence, "capability observation evidence must not be null");
        Objects.requireNonNull(provenance, "capability observation provenance must not be null");
        if (description.isBlank() || provenance.isBlank()) throw new IllegalArgumentException("capability observation description and provenance must not be blank");
        candidates = List.copyOf(candidates); evidence = List.copyOf(evidence);
    }
}
