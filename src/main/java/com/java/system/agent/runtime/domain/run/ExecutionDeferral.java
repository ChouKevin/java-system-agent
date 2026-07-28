package com.java.system.agent.runtime.domain.run;

import java.time.Instant;
import java.util.Objects;

/**
 * 外部執行延後後供 inbox 排程恢復的安全資訊
 */
public record ExecutionDeferral(Instant retryAt, ExecutionDeferralReason reason) {

    public ExecutionDeferral {
        Objects.requireNonNull(retryAt, "execution deferral retry time must not be null");
        Objects.requireNonNull(reason, "execution deferral reason must not be null");
    }
}
