package com.java.system.agent.runtime.domain.run;

import java.util.Objects;

/**
 * 尚未持久化的 Agent Run 事件與候選狀態
 */
public record AgentTransition(AgentEvent event, AgentRunState candidateState) {
    public AgentTransition {
        Objects.requireNonNull(event, "agent event must not be null");
        Objects.requireNonNull(candidateState, "candidate agent run state must not be null");
    }
}
