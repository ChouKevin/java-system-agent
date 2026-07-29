package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 模型提出下一個動作所需的 runtime 已驗證快照
 */
public record AgentPromptContext(
        String originalQuestion,
        SessionHistory sessionHistory,
        AnalysisRunId runId,
        AnalysisAttemptId attemptId,
        Map<CapabilityHandle, CapabilityPolicy> issuedCapabilities,
        Map<CandidateHandle, IssuedCandidate> issuedCandidates,
        Map<EvidenceHandle, IssuedEvidence> issuedEvidence,
        Map<ObservationId, AgentObservation> observations,
        Optional<String> latestRejection,
        AttemptBudget budget) {

    public AgentPromptContext {
        Objects.requireNonNull(originalQuestion, "original question must not be null");
        Objects.requireNonNull(sessionHistory, "session history must not be null");
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(attemptId, "analysis attempt ID must not be null");
        Objects.requireNonNull(latestRejection, "latest rejection must not be null");
        Objects.requireNonNull(budget, "attempt budget must not be null");
        if (originalQuestion.isBlank()) {
            throw new IllegalArgumentException("original question must not be blank");
        }
        latestRejection = latestRejection.map(AgentPromptContext::requiredText);
        issuedCapabilities = immutableMap(issuedCapabilities, "issued capability");
        issuedCandidates = immutableMap(issuedCandidates, "issued candidate");
        issuedEvidence = immutableMap(issuedEvidence, "issued evidence");
        observations = immutableMap(observations, "observation");
    }

    private static String requiredText(String value) {
        Objects.requireNonNull(value, "latest rejection description must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("latest rejection description must not be blank");
        }
        return value;
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values, String entryDescription) {
        Objects.requireNonNull(values, entryDescription + " map must not be null");
        Map<K, V> copied = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : values.entrySet()) {
            copied.put(Objects.requireNonNull(entry.getKey(), entryDescription + " key must not be null"),
                    Objects.requireNonNull(entry.getValue(), entryDescription + " value must not be null"));
        }
        return Collections.unmodifiableMap(copied);
    }
}
