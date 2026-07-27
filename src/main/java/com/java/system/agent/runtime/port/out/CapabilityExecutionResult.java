package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.candidate.AnalysisCandidate;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.observation.CapabilityObservation;

import java.util.List;
import java.util.Objects;

/**
 * Capability executor 尚未配發 runtime handle 的原始執行結果
 */
public sealed interface CapabilityExecutionResult permits CapabilityExecutionResult.Succeeded,
        CapabilityExecutionResult.Failed {

    record Succeeded(List<AnalysisCandidate> discoveredCandidates, List<EvidenceRef> evidence,
                     List<CapabilityObservation> observations) implements CapabilityExecutionResult {
        public Succeeded {
            discoveredCandidates = immutableList(discoveredCandidates, "discovered candidate");
            evidence = immutableList(evidence, "capability evidence");
            observations = immutableList(observations, "capability observation");
        }
    }

    record Failed(CapabilityExecutionFailure failure) implements CapabilityExecutionResult {
        public Failed {
            Objects.requireNonNull(failure, "capability execution failure must not be null");
        }
    }

    private static <T> List<T> immutableList(List<T> values, String description) {
        Objects.requireNonNull(values, description + " list must not be null");
        for (T value : values) {
            Objects.requireNonNull(value, description + " must not contain null elements");
        }
        return List.copyOf(values);
    }
}
