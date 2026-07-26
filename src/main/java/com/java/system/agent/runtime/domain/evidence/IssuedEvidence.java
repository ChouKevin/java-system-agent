package com.java.system.agent.runtime.domain.evidence;

import com.java.system.agent.runtime.domain.handle.EvidenceHandle;

import java.util.Objects;

/**
 * 已由 runtime 配發 handle 並釘選 revision 的證據
 */
public record IssuedEvidence(EvidenceHandle handle, EvidenceRef evidence) {

    public IssuedEvidence {
        Objects.requireNonNull(handle, "issued evidence handle must not be null");
        Objects.requireNonNull(evidence, "issued evidence must not be null");
        if (!handle.binding().revisionVector().matches(evidence.repositoryId(), evidence.repositoryRevision())) {
            throw new IllegalArgumentException("evidence revision must match handle binding revision vector");
        }
    }
}
