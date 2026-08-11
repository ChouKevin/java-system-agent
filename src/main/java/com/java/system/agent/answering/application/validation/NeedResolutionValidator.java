package com.java.system.agent.answering.application.validation;

import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.NeedResolution;
import com.java.system.agent.answering.domain.plan.QuestionPlan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 驗證 QuestionPlan 解析結果只引用目前 authority context 的資料
 */
public final class NeedResolutionValidator {

    public void validate(
            QuestionPlan plan,
            List<NeedResolution> resolutions,
            AnswerDocument document,
            Map<EvidenceHandle, IssuedEvidence> issuedEvidence,
            Map<ObservationId, AgentObservation> observations,
            HandleBinding currentBinding) {
        Objects.requireNonNull(plan, "question plan must not be null");
        Objects.requireNonNull(resolutions, "need resolutions must not be null");
        Objects.requireNonNull(document, "answer document must not be null");
        Objects.requireNonNull(issuedEvidence, "issued evidence must not be null");
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(currentBinding, "current binding must not be null");
        validateResolutionIdentifiers(plan, resolutions);
        Map<String, IssuedEvidence> evidenceByValue = evidenceByValue(issuedEvidence);
        Set<EvidenceHandleRef> citedEvidence = citedEvidence(document);
        for (NeedResolution resolution : resolutions) {
            validateEvidence(resolution, evidenceByValue, currentBinding, citedEvidence);
            validateObservations(resolution, observations);
        }
    }

    private static void validateResolutionIdentifiers(QuestionPlan plan, List<NeedResolution> resolutions) {
        List<InformationNeedId> planIdentifiers = plan.needs().stream().map(InformationNeed::id).toList();
        List<InformationNeedId> resolutionIdentifiers = resolutions.stream().map(NeedResolution::needId).toList();
        Set<InformationNeedId> uniqueResolutionIdentifiers = new HashSet<>(resolutionIdentifiers);
        if (uniqueResolutionIdentifiers.size() != resolutionIdentifiers.size()
                || !planIdentifiers.equals(resolutionIdentifiers)) {
            throw new NeedResolutionContractException(ActionRejectionCode.QUESTION_PLAN_RESOLUTION_MISMATCH);
        }
    }

    private static Map<String, IssuedEvidence> evidenceByValue(Map<EvidenceHandle, IssuedEvidence> issuedEvidence) {
        Map<String, IssuedEvidence> byValue = new HashMap<>();
        for (IssuedEvidence issued : issuedEvidence.values()) {
            IssuedEvidence previous = byValue.put(issued.handle().value(), issued);
            if (Objects.nonNull(previous)) {
                throw new IllegalArgumentException("issued evidence values must be unique within an attempt");
            }
        }
        return Map.copyOf(byValue);
    }

    private static Set<EvidenceHandleRef> citedEvidence(AnswerDocument document) {
        Set<EvidenceHandleRef> citations = new HashSet<>();
        for (AnswerStatement statement : document.statements()) {
            citations.addAll(statement.citations());
        }
        return Set.copyOf(citations);
    }

    private static void validateEvidence(
            NeedResolution resolution,
            Map<String, IssuedEvidence> issuedEvidence,
            HandleBinding currentBinding,
            Set<EvidenceHandleRef> citedEvidence) {
        for (EvidenceHandleRef reference : resolution.evidence()) {
            IssuedEvidence resolved = issuedEvidence.get(reference.value());
            if (Objects.isNull(resolved) || !resolved.handle().binding().equals(currentBinding)) {
                throw new NeedResolutionContractException(ActionRejectionCode.UNKNOWN_RESOLUTION_EVIDENCE);
            }
            if (!citedEvidence.contains(reference)) {
                throw new NeedResolutionContractException(ActionRejectionCode.UNCITED_RESOLUTION_EVIDENCE);
            }
        }
    }

    private static void validateObservations(
            NeedResolution resolution,
            Map<ObservationId, AgentObservation> observations) {
        for (ObservationId observationId : resolution.observations()) {
            if (Objects.isNull(observations.get(observationId))) {
                throw new NeedResolutionContractException(ActionRejectionCode.UNKNOWN_RESOLUTION_OBSERVATION);
            }
        }
    }
}
