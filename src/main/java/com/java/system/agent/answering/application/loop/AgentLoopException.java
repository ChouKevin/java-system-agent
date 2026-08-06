package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;

import java.util.Objects;

/**
 * Agent transition 無法提交時保留最後已提交狀態與失敗事件的例外
 */
public final class AgentLoopException extends IllegalStateException {

    private final AgentRunState lastCommittedState;
    private final AgentEvent failedEvent;

    public AgentLoopException(
            String message,
            AgentRunState lastCommittedState,
            AgentEvent failedEvent,
            Throwable cause) {
        super(message, cause);
        this.lastCommittedState = Objects.requireNonNull(
                lastCommittedState, "last committed agent state must not be null");
        this.failedEvent = Objects.requireNonNull(failedEvent, "failed agent event must not be null");
    }

    public AgentRunState lastCommittedState() {
        return lastCommittedState;
    }

    public AgentEvent failedEvent() {
        return failedEvent;
    }
}
