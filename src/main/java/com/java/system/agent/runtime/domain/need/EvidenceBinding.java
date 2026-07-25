package com.java.system.agent.runtime.domain.need;

import com.java.system.agent.runtime.domain.evidence.EvidenceRef;

import java.util.Objects;

/**
 * 從 {@link InformationNeedId} 到滿足它的證據的連結
 *
 * <p>刻意放在 {@code need} 而非 {@code evidence}：{@code InformationNeed} 本身已攜帶
 * {@code evidence} 的 {@code SemanticTarget}，若把這條連結也放到 {@code evidence}，
 * 會讓 {@code evidence} 反過來引用 {@code need}，形成 need ↔ evidence 循環依賴
 * 放在 need 這邊，依賴方向維持單向，不要因為「看起來更該屬於 evidence」而搬過去</p>
 */
public record EvidenceBinding(InformationNeedId informationNeedId, EvidenceRef evidenceRef) {

    public EvidenceBinding {
        Objects.requireNonNull(informationNeedId, "information need ID must not be null");
        Objects.requireNonNull(evidenceRef, "evidence ref must not be null");
    }
}
