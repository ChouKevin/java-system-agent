package com.java.system.agent.answering.domain.capability;

import java.util.Objects;

/**
 * 規劃工具邊界建立的 capability 專屬 canonical payload，answering 不得解析或檢查其編碼值
 * 值只含 capability 執行參數，QUERY 驗證通過後才可由 capability dispatcher 解碼
 */
public record CapabilityInputPayload(String value) {

    public CapabilityInputPayload {
        Objects.requireNonNull(value, "capability input payload value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("capability input payload value must not be blank");
        }
    }
}
