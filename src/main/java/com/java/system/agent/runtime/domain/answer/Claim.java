package com.java.system.agent.runtime.domain.answer;

import java.util.Objects;
import java.util.Set;

/**
 * 回答中的一項主張，以及它所引用的證據代號
 *
 * <p>{@code citations} 可為空——沒有引用任何證據的主張，會在驗證階段被判為 UNSUPPORTED</p>
 */
public record Claim(ClaimId id, String text, Set<EvidenceHandle> citations) {

    public Claim {
        Objects.requireNonNull(id, "claim ID must not be null");
        Objects.requireNonNull(text, "claim text must not be null");
        Objects.requireNonNull(citations, "claim citations must not be null");
        citations = Set.copyOf(citations);
    }
}
