package com.java.system.agent.interaction.domain;

/**
 * 上游來源訊息的 opaque 去重識別碼
 */
public record SourceMessageId(String value) {

    public SourceMessageId {
        value = InboxMessageId.requiredOpaqueValue(value, "source message ID");
    }
}
