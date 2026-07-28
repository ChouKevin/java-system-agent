package com.java.system.agent.inbox.application;

import com.java.system.agent.inbox.domain.SourceEventConflictScope;

/**
 * Inbox owning boundary 發出的 bounded lifecycle metric event
 */
public interface InboxLifecycleMetrics {

    InboxLifecycleMetrics NO_OP = new InboxLifecycleMetrics() {
    };

    default void sourceAccepted() {
    }

    default void sourceConflict(SourceEventConflictScope scope) {
    }

    default void capacityDeferred() {
    }

    default void infrastructureFailure() {
    }

    default void deliveryRetried() {
    }

    default void deliveryBlocked() {
    }
}
