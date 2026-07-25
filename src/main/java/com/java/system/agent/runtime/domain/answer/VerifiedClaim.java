package com.java.system.agent.runtime.domain.answer;

import java.util.Objects;

/**
 * {@link Claim} 附上判定結果後的型態，是 {@link Answer} 實際持有的主張單位
 *
 * <p>刻意內嵌 status 與 reason 而非另外持有 {@code ClaimVerdict}——
 * Answer 需要的是「主張＋判定」的完整組合，不需要再從 claimId 反查判定</p>
 */
public record VerifiedClaim(Claim claim, ClaimVerdictStatus status, String reason) {

    public VerifiedClaim {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(status, "claim verdict status must not be null");
        Objects.requireNonNull(reason, "claim verdict reason must not be null");
    }
}
