package com.java.system.agent.slack;

import java.time.Duration;

/**
 * Slack transport owning boundary 發出的 bounded lifecycle metric event
 */
public interface SlackLifecycleMetrics {

    SlackLifecycleMetrics NO_OP = new SlackLifecycleMetrics() {
    };

    default void unsupportedMentionIgnored() {
    }

    default void socketAcceptance(Duration duration) {
    }

    default void socketAcceptanceFailure() {
    }

    default void providerRateLimited() {
    }
}
