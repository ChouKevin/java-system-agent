package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;

import java.util.Map;
import java.util.Objects;

/**
 * 動作驗證所需的 runtime 已配發契約快照
 */
public record AgentValidationContext(Map<CapabilityHandle, CapabilityDescriptor> capabilities,
        Map<CandidateHandle, IssuedCandidate> candidates, Map<EvidenceHandle, IssuedEvidence> evidence,
        Map<ObservationId, AgentObservation> observations, HandleBinding currentBinding, AttemptBudget budget,
        boolean finalResponseMode) {
    public AgentValidationContext {
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(currentBinding, "current binding must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        validateCandidateEntries(candidates);
        validateEvidenceEntries(evidence);
        validateObservationEntries(observations);
        capabilities = Map.copyOf(capabilities);
        candidates = Map.copyOf(candidates);
        evidence = Map.copyOf(evidence);
        observations = Map.copyOf(observations);
    }

    private static void validateCandidateEntries(Map<CandidateHandle, IssuedCandidate> candidates) {
        for (Map.Entry<CandidateHandle, IssuedCandidate> entry : candidates.entrySet()) {
            CandidateHandle handle = Objects.requireNonNull(entry.getKey(), "candidate handle must not be null");
            IssuedCandidate issued = Objects.requireNonNull(entry.getValue(), "issued candidate must not be null");
            if (!handle.equals(issued.handle())) {
                throw new IllegalArgumentException("candidate snapshot key must match issued candidate handle");
            }
        }
    }

    private static void validateEvidenceEntries(Map<EvidenceHandle, IssuedEvidence> evidence) {
        for (Map.Entry<EvidenceHandle, IssuedEvidence> entry : evidence.entrySet()) {
            EvidenceHandle handle = Objects.requireNonNull(entry.getKey(), "evidence handle must not be null");
            IssuedEvidence issued = Objects.requireNonNull(entry.getValue(), "issued evidence must not be null");
            if (!handle.equals(issued.handle())) {
                throw new IllegalArgumentException("evidence snapshot key must match issued evidence handle");
            }
        }
    }

    private static void validateObservationEntries(Map<ObservationId, AgentObservation> observations) {
        for (Map.Entry<ObservationId, AgentObservation> entry : observations.entrySet()) {
            ObservationId id = Objects.requireNonNull(entry.getKey(), "observation ID must not be null");
            AgentObservation observation = Objects.requireNonNull(entry.getValue(), "agent observation must not be null");
            if (!id.equals(observation.id())) {
                throw new IllegalArgumentException("observation snapshot key must match agent observation ID");
            }
        }
    }
}
