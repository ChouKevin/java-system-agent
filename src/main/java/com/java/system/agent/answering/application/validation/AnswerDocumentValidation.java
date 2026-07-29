package com.java.system.agent.answering.application.validation;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationId;
import java.util.Map;
import java.util.Objects;

/** 回答文件實際引用到的受信任證據與觀察快照 */
public record AnswerDocumentValidation(AnswerDocument document, Map<EvidenceHandleRef, IssuedEvidence> citedEvidence, Map<ObservationId, AgentObservation> referencedObservations) {
    public AnswerDocumentValidation {
        Objects.requireNonNull(document, "answer document must not be null");
        Objects.requireNonNull(citedEvidence, "cited evidence must not be null");
        Objects.requireNonNull(referencedObservations, "referenced observations must not be null");
        citedEvidence = Map.copyOf(citedEvidence);
        referencedObservations = Map.copyOf(referencedObservations);
    }
}
