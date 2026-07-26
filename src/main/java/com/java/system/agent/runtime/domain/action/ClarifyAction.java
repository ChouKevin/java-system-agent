package com.java.system.agent.runtime.domain.action;

import com.java.system.agent.runtime.domain.handle.CandidateHandle;

import java.util.List;
import java.util.Objects;

/**
 * 請使用者在既有候選範圍內補充資訊的動作
 */
public record ClarifyAction(String question, List<CandidateHandle> candidates, String reason) implements AgentAction {
    public ClarifyAction {
        Objects.requireNonNull(question, "clarify action question must not be null");
        Objects.requireNonNull(candidates, "clarify action candidates must not be null");
        Objects.requireNonNull(reason, "clarify action reason must not be null");
        if (question.isBlank() || reason.isBlank()) throw new IllegalArgumentException("clarify action question and reason must not be blank");
        candidates = List.copyOf(candidates);
    }
}
