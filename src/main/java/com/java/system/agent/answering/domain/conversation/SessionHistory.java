package com.java.system.agent.answering.domain.conversation;

import java.util.List;
import java.util.Objects;

/**
 * 只可 append 的完整 session 歷史快照
 */
public record SessionHistory(List<ConversationTurn> turns) {

    public SessionHistory {
        Objects.requireNonNull(turns, "session turns must not be null");
        turns = List.copyOf(turns);
        for (ConversationTurn turn : turns) {
            Objects.requireNonNull(turn, "conversation turn must not be null");
        }
    }

    public static SessionHistory empty() {
        return new SessionHistory(List.of());
    }
}
