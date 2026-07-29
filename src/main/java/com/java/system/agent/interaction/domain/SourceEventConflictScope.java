package com.java.system.agent.interaction.domain;

/**
 * 來源 payload 與既有 immutable 事件不一致的識別範圍
 */
public enum SourceEventConflictScope {
    TRANSPORT_EVENT_ID,
    CANONICAL_MESSAGE_ID
}
