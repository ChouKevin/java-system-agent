package com.java.system.agent.runtime.domain.answer;

import com.java.system.agent.runtime.domain.need.EvidenceBinding;

import java.util.Objects;

/**
 * {@link EvidenceHandle} 與其實際證據內容 {@code EvidenceBinding} 的對應
 *
 * <p>組合與驗證階段透過 handle 引用證據，而不是直接持有 {@code EvidenceBinding}，
 * 讓 {@link Claim#citations()} 保持輕量</p>
 */
public record CitableEvidence(EvidenceHandle handle, EvidenceBinding binding) {

    public CitableEvidence {
        Objects.requireNonNull(handle, "evidence handle must not be null");
        Objects.requireNonNull(binding, "evidence binding must not be null");
    }
}
