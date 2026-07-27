package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * verifier 可使用的完整已發行資料與文件精確引用
 */
public record AnswerVerificationContext(String question, SessionHistory sessionHistory, AnswerDocument document,
                                        List<IssuedEvidence> availableEvidence,
                                        List<AgentObservation> availableObservations,
                                        List<IssuedEvidence> citedEvidence,
                                        List<AgentObservation> referencedObservations) {

    public AnswerVerificationContext {
        Objects.requireNonNull(question, "verification question must not be null");
        Objects.requireNonNull(sessionHistory, "verification session history must not be null");
        Objects.requireNonNull(document, "verification answer document must not be null");
        if (question.isBlank()) {
            throw new IllegalArgumentException("verification question must not be blank");
        }
        availableEvidence = immutableList(availableEvidence, "available evidence");
        availableObservations = immutableList(availableObservations, "available observation");
        citedEvidence = immutableList(citedEvidence, "cited evidence");
        referencedObservations = immutableList(referencedObservations, "referenced observation");
        validate(document, availableEvidence, availableObservations, citedEvidence, referencedObservations);
    }

    private static <T> List<T> immutableList(List<T> values, String description) {
        Objects.requireNonNull(values, description + " list must not be null");
        for (T value : values) {
            Objects.requireNonNull(value, description + " must not contain null elements");
        }
        return List.copyOf(values);
    }

    private static void validate(AnswerDocument document, List<IssuedEvidence> availableEvidence,
                                 List<AgentObservation> availableObservations, List<IssuedEvidence> citedEvidence,
                                 List<AgentObservation> referencedObservations) {
        Set<EvidenceHandle> availableEvidenceHandles = evidenceHandles(availableEvidence, "available evidence");
        Set<ObservationId> availableObservationIds = observationIds(availableObservations, "available observation");
        Set<EvidenceHandle> citedEvidenceHandles = evidenceHandles(citedEvidence, "cited evidence");
        Set<ObservationId> referencedObservationIds = observationIds(referencedObservations, "referenced observation");
        Set<EvidenceHandle> expectedEvidenceHandles = new LinkedHashSet<>();
        Set<ObservationId> expectedObservationIds = new LinkedHashSet<>();
        document.statements().forEach(statement -> {
            expectedEvidenceHandles.addAll(statement.citations());
            expectedObservationIds.addAll(statement.observationIds());
        });
        if (!availableEvidenceHandles.containsAll(citedEvidenceHandles)
                || !availableObservationIds.containsAll(referencedObservationIds)
                || !availableEvidence.containsAll(citedEvidence)
                || !availableObservations.containsAll(referencedObservations)
                || !expectedEvidenceHandles.equals(citedEvidenceHandles)
                || !expectedObservationIds.equals(referencedObservationIds)) {
            throw new IllegalArgumentException("verification context must contain exact available document references");
        }
    }

    private static Set<EvidenceHandle> evidenceHandles(List<IssuedEvidence> evidence, String description) {
        Set<EvidenceHandle> handles = new LinkedHashSet<>();
        for (IssuedEvidence value : evidence) {
            if (!handles.add(value.handle())) {
                throw new IllegalArgumentException(description + " handles must be unique");
            }
        }
        return handles;
    }

    private static Set<ObservationId> observationIds(List<AgentObservation> observations, String description) {
        Set<ObservationId> ids = new LinkedHashSet<>();
        for (AgentObservation value : observations) {
            if (!ids.add(value.id())) {
                throw new IllegalArgumentException(description + " IDs must be unique");
            }
        }
        return ids;
    }
}
