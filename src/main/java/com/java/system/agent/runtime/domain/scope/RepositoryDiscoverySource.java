package com.java.system.agent.runtime.domain.scope;

/**
 * 一個 repository 被納入 {@link RepositoryScope} 的來源
 *
 * <p>{@code SEMANTIC_EVIDENCE} 是唯一允許在分析執行中途觸發 scope 擴張的來源，
 * 其餘皆只能出現在 attempt 準備階段的初始選擇</p>
 */
public enum RepositoryDiscoverySource {
    USER,
    QUESTION_UNDERSTANDING,
    REPOSITORY_METADATA,
    KNOWLEDGE,
    SEMANTIC_EVIDENCE
}
