package com.java.system.agent.runtime.domain.conversation;

import java.util.Objects;

/**
 * 對話參與者的傳輸層無關穩定識別
 */
public record ParticipantRef(String sourceType, String participantKey) {

    public ParticipantRef {
        sourceType = normalize(sourceType, "participant source type");
        participantKey = normalize(participantKey, "participant key");
    }

    /**
     * 產生只含穩定識別的提示標籤
     */
    public String promptLabel() {
        return "participant[" + sourceType + ":" + participantKey + "]";
    }

    private static String normalize(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return normalized;
    }
}
