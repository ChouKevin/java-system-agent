package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;

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
                                        List<AgentObservation> referencedObservations,
                                        List<EvidenceCapabilityProvenance> evidenceProvenance) {

    public AnswerVerificationContext(
            String question,
            SessionHistory sessionHistory,
            AnswerDocument document,
            List<IssuedEvidence> availableEvidence,
            List<AgentObservation> availableObservations,
            List<IssuedEvidence> citedEvidence,
            List<AgentObservation> referencedObservations) {
        this(question, sessionHistory, document, availableEvidence, availableObservations, citedEvidence,
                referencedObservations, List.of());
    }

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
        evidenceProvenance = immutableList(evidenceProvenance, "evidence provenance");
        validate(document, availableEvidence, availableObservations, citedEvidence, referencedObservations,
                evidenceProvenance);
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
                                 List<AgentObservation> referencedObservations,
                                 List<EvidenceCapabilityProvenance> evidenceProvenance) {
        Set<String> availableEvidenceValues = evidenceValues(availableEvidence, "available evidence");
        Set<ObservationId> availableObservationIds = observationIds(availableObservations, "available observation");
        Set<String> citedEvidenceValues = evidenceValues(citedEvidence, "cited evidence");
        Set<ObservationId> referencedObservationIds = observationIds(referencedObservations, "referenced observation");
        Set<String> expectedEvidenceValues = new LinkedHashSet<>();
        Set<ObservationId> expectedObservationIds = new LinkedHashSet<>();
        document.statements().forEach(statement -> {
            expectedEvidenceValues.addAll(statement.citations().stream().map(evidenceHandleReference -> evidenceHandleReference.value()).toList());
            expectedObservationIds.addAll(statement.observationIds());
        });
        if (!availableEvidenceValues.containsAll(citedEvidenceValues)
                || !availableObservationIds.containsAll(referencedObservationIds)
                || !expectedEvidenceValues.equals(citedEvidenceValues)
                || !expectedObservationIds.equals(referencedObservationIds)) {
            throw new IllegalArgumentException("verification context must contain exact available document references");
        }
        for (EvidenceCapabilityProvenance provenance : evidenceProvenance) {
            if (!availableEvidenceValues.contains(provenance.evidenceHandle().value())) {
                throw new IllegalArgumentException(
                        "evidence capability provenance must reference available evidence");
            }
        }
    }

    private static Set<String> evidenceValues(List<IssuedEvidence> evidence, String description) {
        Set<String> values = new LinkedHashSet<>();
        for (IssuedEvidence value : evidence) {
            if (!values.add(value.handle().value())) {
                throw new IllegalArgumentException(description + " handle values must be unique");
            }
        }
        return values;
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
