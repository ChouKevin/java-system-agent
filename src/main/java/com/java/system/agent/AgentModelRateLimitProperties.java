package com.java.system.agent;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * Agent runtime 在啟用 profile 時使用的模型 provider 容量設定
 */
@Validated
@ConfigurationProperties("agent.model.rate-limit")
public record AgentModelRateLimitProperties(
        @DefaultValue("15") @Positive int requestsPerMinute,
        @DefaultValue("250000") @Positive int inputTokensPerMinute,
        @DefaultValue("500") @Positive int requestsPerDay,
        @DefaultValue("1m") @NotNull Duration providerRetryFallback) {

    @AssertTrue(message = "provider retry fallback must be positive")
    public boolean hasPositiveProviderRetryFallback() {
        return Objects.nonNull(providerRetryFallback)
                && !providerRetryFallback.isZero()
                && !providerRetryFallback.isNegative();
    }
}
