package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.port.out.SessionPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 以 insertion-order map 模擬 append-only session 儲存的測試替身
 */
public final class FakeSessionAdapter implements SessionPort {

    private final Map<SessionId, LinkedHashMap<AnalysisRunId, ConversationTurn>> turns =
            new LinkedHashMap<>();

    @Override
    public synchronized SessionHistory read(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "session ID must not be null");
        Map<AnalysisRunId, ConversationTurn> sessionHistory = turns.get(sessionId);
        if (Objects.isNull(sessionHistory)) {
            return SessionHistory.empty();
        }
        return new SessionHistory(sessionHistory.values().stream().toList());
    }

    @Override
    public synchronized void append(SessionId sessionId, ConversationTurn turn) {
        Objects.requireNonNull(sessionId, "session ID must not be null");
        Objects.requireNonNull(turn, "accepted conversation turn must not be null");
        LinkedHashMap<AnalysisRunId, ConversationTurn> sessionHistory = turns.computeIfAbsent(sessionId,
                unused -> new LinkedHashMap<>());
        ConversationTurn existing = sessionHistory.get(turn.runId());
        if (Objects.isNull(existing)) {
            sessionHistory.put(turn.runId(), turn);
            return;
        }
        if (!existing.equals(turn)) {
            throw new IllegalStateException("session run already has conflicting accepted content");
        }
    }
}
