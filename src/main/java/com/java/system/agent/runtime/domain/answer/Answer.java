package com.java.system.agent.runtime.domain.answer;

import java.util.List;
import java.util.Objects;

/**
 * 組合並驗證完成的最終回答
 *
 * <p>{@code claims} 保留每一項主張與其判定，包括 UNSUPPORTED 的主張——
 * 刪除被拒絕的主張，會讓下一輪組合失去「這件事已經被拒絕過」的訊號，
 * 可能重複主張同一件缺乏證據支持的事</p>
 */
public record Answer(String text, List<VerifiedClaim> claims) {

    public Answer {
        Objects.requireNonNull(text, "answer text must not be null");
        Objects.requireNonNull(claims, "answer claims must not be null");
        claims = claims.stream()
                .map(claim -> Objects.requireNonNull(claim, "verified claim must not be null"))
                .toList();
    }

    public List<VerifiedClaim> supportedClaims() {
        return claims.stream()
                .filter(claim -> claim.status() == ClaimVerdictStatus.SUPPORTED)
                .toList();
    }

    public List<VerifiedClaim> unsupportedClaims() {
        return claims.stream()
                .filter(claim -> claim.status() == ClaimVerdictStatus.UNSUPPORTED)
                .toList();
    }
}
