package com.java.system.agent.runtime.application.validation;

import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandleRef;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 只解析回答實際引用項目的確定性證據與觀察閘門
 */
public final class AnswerDocumentValidator {

    public AnswerDocumentValidation validate(
            AnswerDocument document,
            Map<EvidenceHandle, IssuedEvidence> evidence,
            Map<ObservationId, AgentObservation> observations,
            HandleBinding binding) {
        Objects.requireNonNull(document, "answer document must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(binding, "binding must not be null");
        Map<String, IssuedEvidence> evidenceByValue = issuedEvidenceByValue(evidence);
        Map<EvidenceHandleRef, IssuedEvidence> cited = new LinkedHashMap<>();
        Map<ObservationId, AgentObservation> referenced = new LinkedHashMap<>();
        for (AnswerStatement statement : document.statements()) {
            for (EvidenceHandleRef reference : statement.citations()) {
                IssuedEvidence issued = evidenceByValue.get(reference.value());
                if (Objects.isNull(issued)) {
                    throw new AnswerDocumentContractException(
                            ActionRejectionCode.UNKNOWN_EVIDENCE, "answer cites unknown evidence");
                }
                EvidenceHandle handle = issued.handle();
                if (!sameAttempt(handle.binding(), binding)) {
                    throw new AnswerDocumentContractException(
                            ActionRejectionCode.CROSS_ATTEMPT, "answer cites evidence from another attempt");
                }
                if (!handle.binding().revisionVector().equals(binding.revisionVector())) {
                    throw new AnswerDocumentContractException(
                            ActionRejectionCode.STALE_REVISION, "answer cites evidence from a stale revision");
                }
                if (!binding.revisionVector().matches(issued.evidence().repositoryId(), issued.evidence().repositoryRevision())) {
                    throw new AnswerDocumentContractException(
                            ActionRejectionCode.STALE_REVISION, "answer cites stale evidence");
                }
                cited.put(reference, issued);
            }
            for (ObservationId id : statement.observationIds()) {
                AgentObservation observation = observations.get(id);
                if (Objects.isNull(observation)) {
                    throw new AnswerDocumentContractException(
                            ActionRejectionCode.UNKNOWN_OBSERVATION, "answer references an unknown observation");
                }
                referenced.put(id, observation);
            }
            if (statement.type() == StatementType.LIMITATION && statement.observationIds().isEmpty()) {
                throw new AnswerDocumentContractException(
                        ActionRejectionCode.INVALID_ANSWER_DOCUMENT,
                        "limitation statements require a relevant observation");
            }
        }
        return new AnswerDocumentValidation(document, cited, referenced);
    }

    private static Map<String, IssuedEvidence> issuedEvidenceByValue(Map<EvidenceHandle, IssuedEvidence> evidence) {
        Map<String, IssuedEvidence> byValue = new LinkedHashMap<>();
        for (IssuedEvidence issued : evidence.values()) {
            IssuedEvidence previous = byValue.put(issued.handle().value(), issued);
            if (Objects.nonNull(previous)) {
                throw new IllegalArgumentException("issued evidence values must be unique within an attempt");
            }
        }
        return Map.copyOf(byValue);
    }

    private static boolean sameAttempt(HandleBinding left, HandleBinding right) {
        return left.runId().equals(right.runId()) && left.attemptId().equals(right.attemptId());
    }
}
