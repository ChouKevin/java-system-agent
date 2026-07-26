package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationId;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Objects;

/**
 * 驗證一份回答文件時可使用的精確 runtime 已接受資料
 */
public record AnswerVerificationContext(
        String originalQuestion,
        AnswerDocument document,
        List<IssuedEvidence> citedEvidence,
        List<AgentObservation> referencedObservations) {

    public AnswerVerificationContext {
        Objects.requireNonNull(originalQuestion, "original question must not be null");
        Objects.requireNonNull(document, "answer document must not be null");
        if (originalQuestion.isBlank()) {
            throw new IllegalArgumentException("original question must not be blank");
        }
        citedEvidence = immutableList(citedEvidence, "cited evidence");
        referencedObservations = immutableList(referencedObservations, "referenced observation");
        validateExactReferences(document, citedEvidence, referencedObservations);
    }

    private static <T> List<T> immutableList(List<T> values, String valueDescription) {
        Objects.requireNonNull(values, valueDescription + " list must not be null");
        for (T value : values) {
            Objects.requireNonNull(value, valueDescription + " must not contain null elements");
        }
        return List.copyOf(values);
    }

    private static void validateExactReferences(AnswerDocument document, List<IssuedEvidence> citedEvidence,
                                                List<AgentObservation> observations) {
        Set<EvidenceHandle> expectedEvidence = new LinkedHashSet<>();
        Set<ObservationId> expectedObservations = new LinkedHashSet<>();
        document.statements().forEach(statement -> {
            expectedEvidence.addAll(statement.citations());
            expectedObservations.addAll(statement.observationIds());
        });
        Set<EvidenceHandle> actualEvidence = new LinkedHashSet<>();
        for (IssuedEvidence evidence : citedEvidence) {
            if (!actualEvidence.add(evidence.handle())) {
                throw new IllegalArgumentException("cited evidence handles must be unique");
            }
        }
        Set<ObservationId> actualObservations = new LinkedHashSet<>();
        for (AgentObservation observation : observations) {
            if (!actualObservations.add(observation.id())) {
                throw new IllegalArgumentException("referenced observation IDs must be unique");
            }
        }
        if (!expectedEvidence.equals(actualEvidence) || !expectedObservations.equals(actualObservations)) {
            throw new IllegalArgumentException("verification context must exactly match document references");
        }
    }
}
