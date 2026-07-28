package com.java.system.agent.model;

/**
 * Model provider owning boundary 發出的 bounded lifecycle metric event
 */
public interface ModelLifecycleMetrics {

    ModelLifecycleMetrics NO_OP = new ModelLifecycleMetrics() {
    };

    default void requested(long estimatedTokens) {
    }

    default void providerRateLimited() {
    }
}
