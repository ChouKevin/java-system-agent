package com.java.system.agent.runtime.domain.need;

import java.util.Objects;

/**
 * {@link InformationNeed} 的識別碼
 *
 * <p>可排序，用於 {@code pendingNeeds} 維持穩定順序，並作為 {@link EvidenceBinding} 的關聯鍵</p>
 */
public record InformationNeedId(String value) implements Comparable<InformationNeedId> {

    public InformationNeedId {
        Objects.requireNonNull(value, "information need ID must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("information need ID must not be blank");
        }
    }

    @Override
    public int compareTo(InformationNeedId other) {
        Objects.requireNonNull(other, "information need ID must not be null");
        return value.compareTo(other.value);
    }
}
