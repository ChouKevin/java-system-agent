package com.java.system.agent.inbox.domain;

import java.util.Objects;

/**
 * 可安全持久化的 inbox 處理失敗摘要
 *
 * <p>呼叫端必須提供預先定義的安全摘要，不得傳入 Throwable、堆疊、SQL、headers、provider payload 或機密資訊
 * 此型別只結構性限制為有界的一行 code 與 description，並不語意辨識任意字串內容</p>
 */
public record InboxFailure(String code, String description) {

    public static final int MAX_CODE_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 512;

    public InboxFailure {
        code = boundedSingleLine(code, "failure code", MAX_CODE_LENGTH);
        description = boundedSingleLine(description, "failure description", MAX_DESCRIPTION_LENGTH);
    }

    private static String boundedSingleLine(String value, String fieldName, int maximumLength) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String trimmedValue = value.trim();
        if (trimmedValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (trimmedValue.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " exceeds " + maximumLength + " characters");
        }
        if (trimmedValue.contains("\n") || trimmedValue.contains("\r")) {
            throw new IllegalArgumentException(fieldName + " must be a single line");
        }
        return trimmedValue;
    }
}
