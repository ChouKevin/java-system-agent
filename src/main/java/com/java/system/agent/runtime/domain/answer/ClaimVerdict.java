package com.java.system.agent.runtime.domain.answer;

import java.util.Objects;

/**
 * 驗證階段對單一 {@link Claim} 所下的判定
 *
 * <p>與 {@link Claim} 分離：判定屬於驗證階段的產出，主張本身在組合階段就已定案</p>
 */
public record ClaimVerdict(ClaimId claimId, ClaimVerdictStatus status, String reason) {

    public ClaimVerdict {
        Objects.requireNonNull(claimId, "claim ID must not be null");
        Objects.requireNonNull(status, "claim verdict status must not be null");
        Objects.requireNonNull(reason, "claim verdict reason must not be null");
    }
}
