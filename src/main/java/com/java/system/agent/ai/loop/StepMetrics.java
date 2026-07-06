package com.java.system.agent.ai.loop;

import org.springframework.ai.chat.metadata.Usage;

import java.util.Objects;

/** 單一 model turn 的耗時與 token 用量 */
public record StepMetrics(long durationMillis, int promptTokens, int completionTokens) {

    public static StepMetrics none() {
        return new StepMetrics(0, 0, 0);
    }

    public static StepMetrics of(long durationMillis, Usage usage) {
        if (Objects.isNull(usage)) {
            return new StepMetrics(durationMillis, 0, 0);
        }
        return new StepMetrics(durationMillis,
                zeroIfNull(usage.getPromptTokens()),
                zeroIfNull(usage.getCompletionTokens()));
    }

    private static int zeroIfNull(Integer value) {
        return Objects.requireNonNullElse(value, 0);
    }
}
