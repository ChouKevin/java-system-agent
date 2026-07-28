package com.java.system.agent;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * Slack Agent recurring worker 的 polling 與關閉期限設定
 */
@Validated
@ConfigurationProperties("agent.worker")
public record AgentWorkerProperties(
        @NotNull Duration inboxPollInterval,
        @NotNull Duration deliveryPollInterval,
        @NotNull Duration shutdownGracePeriod,
        @Positive int deliveryMaxAttempts) {

    @AssertTrue(message = "inbox poll interval must be positive")
    public boolean hasPositiveInboxPollInterval() {
        return isPositive(inboxPollInterval);
    }

    @AssertTrue(message = "delivery poll interval must be positive")
    public boolean hasPositiveDeliveryPollInterval() {
        return isPositive(deliveryPollInterval);
    }

    @AssertTrue(message = "shutdown grace period must be positive")
    public boolean hasPositiveShutdownGracePeriod() {
        return isPositive(shutdownGracePeriod);
    }

    private static boolean isPositive(Duration value) {
        return Objects.nonNull(value) && !value.isNegative() && !value.isZero();
    }
}
