package com.java.system.agent.runtime.domain.run;

import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.scope.RevisionVector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一個固定 revision 的 agent attempt 所持有的 runtime 配發資料
 */
public record RunAttempt(
        AnalysisAttemptId attemptId,
        RevisionVector revisionVector,
        Map<CapabilityHandle, CapabilityDescriptor> issuedCapabilities,
        Map<CandidateHandle, IssuedCandidate> issuedCandidates,
        Map<EvidenceHandle, IssuedEvidence> issuedEvidence,
        Map<ObservationId, AgentObservation> observations) {

    public RunAttempt {
        Objects.requireNonNull(attemptId, "analysis attempt ID must not be null");
        Objects.requireNonNull(revisionVector, "revision vector must not be null");
        issuedCapabilities = immutableCapabilities(issuedCapabilities, attemptId, revisionVector);
        issuedCandidates = immutableCandidates(issuedCandidates, attemptId, revisionVector);
        issuedEvidence = immutableEvidence(issuedEvidence, attemptId, revisionVector);
        observations = immutableObservations(observations, issuedCandidates, issuedEvidence, attemptId, revisionVector);
    }

    public static RunAttempt empty(AnalysisAttemptId attemptId) {
        return new RunAttempt(attemptId, RevisionVector.empty(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public RunAttempt withIssuedContext(
            RevisionVector revisions,
            Map<CapabilityHandle, CapabilityDescriptor> capabilities,
            Map<CandidateHandle, IssuedCandidate> candidates,
            Map<EvidenceHandle, IssuedEvidence> evidence,
            Map<ObservationId, AgentObservation> observations) {
        return new RunAttempt(attemptId, revisions, capabilities, candidates, evidence, observations);
    }

    public RunAttempt withObservation(AgentObservation observation) {
        Objects.requireNonNull(observation, "agent observation must not be null");
        Map<ObservationId, AgentObservation> nextObservations = new LinkedHashMap<>(observations);
        if (nextObservations.containsKey(observation.id())) {
            throw new IllegalArgumentException("run attempt observation ID must be unique");
        }
        nextObservations.put(observation.id(), observation);
        return new RunAttempt(attemptId, revisionVector, issuedCapabilities, issuedCandidates, issuedEvidence,
                nextObservations);
    }

    private static Map<CapabilityHandle, CapabilityDescriptor> immutableCapabilities(
            Map<CapabilityHandle, CapabilityDescriptor> capabilities, AnalysisAttemptId attemptId,
            RevisionVector revisionVector) {
        Objects.requireNonNull(capabilities, "issued capabilities must not be null");
        Map<CapabilityHandle, CapabilityDescriptor> copied = new LinkedHashMap<>();
        capabilities.forEach((handle, descriptor) -> copied.put(
                Objects.requireNonNull(handle, "issued capability handle must not be null"),
                Objects.requireNonNull(descriptor, "issued capability descriptor must not be null")));
        copied.keySet().forEach(handle -> validateBinding(handle.binding(), attemptId, revisionVector));
        return Collections.unmodifiableMap(copied);
    }

    private static Map<CandidateHandle, IssuedCandidate> immutableCandidates(
            Map<CandidateHandle, IssuedCandidate> candidates, AnalysisAttemptId attemptId,
            RevisionVector revisionVector) {
        Objects.requireNonNull(candidates, "issued candidates must not be null");
        Map<CandidateHandle, IssuedCandidate> copied = new LinkedHashMap<>();
        candidates.forEach((handle, candidate) -> {
            CandidateHandle checkedHandle = Objects.requireNonNull(handle, "issued candidate handle must not be null");
            IssuedCandidate checkedCandidate = Objects.requireNonNull(candidate, "issued candidate must not be null");
            if (!checkedHandle.equals(checkedCandidate.handle())) {
                throw new IllegalArgumentException("issued candidate key must match its handle");
            }
            copied.put(checkedHandle, checkedCandidate);
        });
        copied.keySet().forEach(handle -> validateBinding(handle.binding(), attemptId, revisionVector));
        return Collections.unmodifiableMap(copied);
    }

    private static Map<EvidenceHandle, IssuedEvidence> immutableEvidence(
            Map<EvidenceHandle, IssuedEvidence> evidence, AnalysisAttemptId attemptId,
            RevisionVector revisionVector) {
        Objects.requireNonNull(evidence, "issued evidence must not be null");
        Map<EvidenceHandle, IssuedEvidence> copied = new LinkedHashMap<>();
        evidence.forEach((handle, issuedEvidence) -> {
            EvidenceHandle checkedHandle = Objects.requireNonNull(handle, "issued evidence handle must not be null");
            IssuedEvidence checkedEvidence = Objects.requireNonNull(issuedEvidence, "issued evidence must not be null");
            if (!checkedHandle.equals(checkedEvidence.handle())) {
                throw new IllegalArgumentException("issued evidence key must match its handle");
            }
            copied.put(checkedHandle, checkedEvidence);
        });
        copied.keySet().forEach(handle -> validateBinding(handle.binding(), attemptId, revisionVector));
        return Collections.unmodifiableMap(copied);
    }

    private static Map<ObservationId, AgentObservation> immutableObservations(
            Map<ObservationId, AgentObservation> observations,
            Map<CandidateHandle, IssuedCandidate> candidates,
            Map<EvidenceHandle, IssuedEvidence> evidence, AnalysisAttemptId attemptId, RevisionVector revisionVector) {
        Objects.requireNonNull(observations, "observations must not be null");
        Map<ObservationId, AgentObservation> copied = new LinkedHashMap<>();
        observations.forEach((id, observation) -> {
            ObservationId checkedId = Objects.requireNonNull(id, "observation ID must not be null");
            AgentObservation checkedObservation = Objects.requireNonNull(observation, "agent observation must not be null");
            if (!checkedId.equals(checkedObservation.id())) {
                throw new IllegalArgumentException("observation key must match its ID");
            }
            checkedObservation.candidateHandles().forEach(handle -> validateBinding(handle.binding(), attemptId,
                    revisionVector));
            checkedObservation.evidenceHandles().forEach(handle -> validateBinding(handle.binding(), attemptId,
                    revisionVector));
            if (!candidates.keySet().containsAll(checkedObservation.candidateHandles())
                    || !evidence.keySet().containsAll(checkedObservation.evidenceHandles())) {
                throw new IllegalArgumentException("observation handles must be issued by the run attempt");
            }
            copied.put(checkedId, checkedObservation);
        });
        return Collections.unmodifiableMap(copied);
    }

    private static void validateBinding(HandleBinding binding, AnalysisAttemptId attemptId, RevisionVector revisionVector) {
        if (!attemptId.equals(binding.attemptId()) || !revisionVector.equals(binding.revisionVector())) {
            throw new IllegalArgumentException("handle binding must match the run attempt");
        }
    }
}
