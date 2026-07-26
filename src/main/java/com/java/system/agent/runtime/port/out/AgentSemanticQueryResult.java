package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.candidate.AnalysisCandidate;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.observation.SemanticObservation;

import java.util.List;
import java.util.Objects;

/**
 * 語意 adapter 尚未配發 runtime handle 的原始查詢結果
 */
public record AgentSemanticQueryResult(
        List<AnalysisCandidate> discoveredCandidates,
        List<EvidenceRef> evidence,
        List<SemanticObservation> observations) {

    public AgentSemanticQueryResult {
        discoveredCandidates = immutableList(discoveredCandidates, "discovered candidate");
        evidence = immutableList(evidence, "semantic evidence");
        observations = immutableList(observations, "semantic observation");
    }

    private static <T> List<T> immutableList(List<T> values, String valueDescription) {
        Objects.requireNonNull(values, valueDescription + " list must not be null");
        for (T value : values) {
            Objects.requireNonNull(value, valueDescription + " must not contain null elements");
        }
        return List.copyOf(values);
    }
}
