package com.java.system.agent.answering.domain.plan;

import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.observation.ObservationId;

import java.util.Objects;
import java.util.Set;

/**
 * 一項資訊需求以證據或不可得觀察表達的解析結果
 */
public record NeedResolution(
        InformationNeedId needId,
        NeedResolutionStatus status,
        Set<EvidenceHandleRef> evidence,
        Set<ObservationId> observations) {

    public NeedResolution {
        Objects.requireNonNull(needId, "need resolution need ID must not be null");
        Objects.requireNonNull(status, "need resolution status must not be null");
        Objects.requireNonNull(evidence, "need resolution evidence must not be null");
        Objects.requireNonNull(observations, "need resolution observations must not be null");
        evidence = Set.copyOf(evidence);
        observations = Set.copyOf(observations);
        if (status == NeedResolutionStatus.SUPPORTED && evidence.isEmpty()) {
            throw new IllegalArgumentException("supported need resolutions require evidence");
        }
        if (status == NeedResolutionStatus.UNAVAILABLE && evidence.isEmpty() && observations.isEmpty()) {
            throw new IllegalArgumentException("unavailable need resolutions require evidence or observations");
        }
    }
}
