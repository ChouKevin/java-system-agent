package com.java.system.agent.interaction.domain;

/**
 * 用來解析或建立 session 的上游來源 opaque 參考
 */
public record SessionSourceRef(String sourceType, String sourceKey) {

    public SessionSourceRef {
        sourceType = InboxMessageId.requiredOpaqueValue(sourceType, "session source type");
        sourceKey = InboxMessageId.requiredOpaqueValue(sourceKey, "session source key");
    }
}
