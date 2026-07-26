package com.java.system.agent.runtime.domain.observation;

import com.java.system.agent.runtime.domain.candidate.AnalysisCandidate;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;

import java.util.List;
import java.util.Objects;

/**
 * 語意服務原始回傳的觀察結果，尚未配發 runtime handle
 */
public record SemanticObservation(ObservationCode code, String description, List<AnalysisCandidate> candidates,
        List<EvidenceRef> evidence, String provenance) {
    public SemanticObservation {
        Objects.requireNonNull(code, "semantic observation code must not be null");
        Objects.requireNonNull(description, "semantic observation description must not be null");
        Objects.requireNonNull(candidates, "semantic observation candidates must not be null");
        Objects.requireNonNull(evidence, "semantic observation evidence must not be null");
        Objects.requireNonNull(provenance, "semantic observation provenance must not be null");
        if (description.isBlank() || provenance.isBlank()) throw new IllegalArgumentException("semantic observation description and provenance must not be blank");
        candidates = List.copyOf(candidates); evidence = List.copyOf(evidence);
    }
}
