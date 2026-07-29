package com.java.system.agent.interaction.domain;

/**
 * 上游傳輸事件的 opaque 去重識別碼
 */
public record TransportEventId(String value) {

    public TransportEventId {
        value = InboxMessageId.requiredOpaqueValue(value, "transport event ID");
    }
}
