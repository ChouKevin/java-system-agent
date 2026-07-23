package com.java.system.agent.analysis.domain;

import java.util.Objects;

public record EvidenceBinding(InformationNeedId informationNeedId, EvidenceRef evidenceRef) {

    public EvidenceBinding {
        Objects.requireNonNull(informationNeedId, "information need ID must not be null");
        Objects.requireNonNull(evidenceRef, "evidence ref must not be null");
    }
}
