package com.java.system.agent.answering.domain.evidence;

import java.util.Objects;

/**
 * 一筆證據對應的 Agent 端儲存位址
 *
 * <p>與 {@link SemanticTarget} 相反：它定址的是 Agent 自己的儲存（例如快取的原始回應），
 * 不會被送回語意服務當作查詢參數</p>
 */
public record ArtifactRef(String digest) {

    public ArtifactRef {
        Objects.requireNonNull(digest, "artifact digest must not be null");
        digest = digest.trim();
        if (digest.isBlank()) {
            throw new IllegalArgumentException("artifact digest must not be blank");
        }
    }
}
