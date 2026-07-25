package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.answer.Claim;

import java.util.List;
import java.util.Objects;

/**
 * {@link AnswerCompositionPort} 產出、尚未經過驗證的回答草稿
 *
 * <p>{@code claims} 此時還沒有判定結果——判定由 {@link ClaimVerificationPort} 補上，
 * 之後才能組成最終的 {@code Answer}</p>
 */
public record AnswerDraft(String text, List<Claim> claims) {

    public AnswerDraft {
        Objects.requireNonNull(text, "answer draft text must not be null");
        Objects.requireNonNull(claims, "answer draft claims must not be null");
    }
}
