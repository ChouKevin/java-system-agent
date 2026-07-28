package com.java.system.agent.inbox.domain.delivery;

import java.time.Instant;
import java.util.Objects;

/**
 * 傳輸 adapter 對單筆 delivery 嘗試的中立分類結果
 */
public sealed interface DeliveryTransportResult {

    /**
     * 外部 provider 已接受 delivery 的結果
     */
    record Delivered(String providerMessageId) implements DeliveryTransportResult {

        public Delivered {
            providerMessageId = DeliveryId.requiredOpaqueValue(providerMessageId, "provider message ID");
        }
    }

    /**
     * 可在指定時間重試的傳輸失敗結果
     */
    record RetryableFailure(Instant retryAt, String category) implements DeliveryTransportResult {

        public RetryableFailure {
            Objects.requireNonNull(retryAt, "retry at must not be null");
            category = DeliveryFailure.boundedSingleLine(
                    category, "delivery failure category", DeliveryFailure.MAX_CATEGORY_LENGTH);
        }
    }

    /**
     * 不應自動重試的安全傳輸失敗結果
     */
    record PermanentFailure(String category, String safeDescription) implements DeliveryTransportResult {

        public PermanentFailure {
            category = DeliveryFailure.boundedSingleLine(
                    category, "delivery failure category", DeliveryFailure.MAX_CATEGORY_LENGTH);
            safeDescription = DeliveryFailure.boundedSingleLine(
                    safeDescription, "delivery failure description", DeliveryFailure.MAX_DESCRIPTION_LENGTH);
        }
    }
}
