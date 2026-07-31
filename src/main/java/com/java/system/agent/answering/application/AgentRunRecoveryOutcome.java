package com.java.system.agent.answering.application;

import java.util.Objects;

/**
 * 已持久化 agent run 復原後交回主迴圈的結果
 */
sealed interface AgentRunRecoveryOutcome permits AgentRunRecoveryOutcome.Active, AgentRunRecoveryOutcome.Terminal {

    record Active(ActiveAgentExecution execution) implements AgentRunRecoveryOutcome {

        public Active {
            Objects.requireNonNull(execution, "active agent execution must not be null");
        }
    }

    record Terminal(AgentLoopResult result) implements AgentRunRecoveryOutcome {

        public Terminal {
            Objects.requireNonNull(result, "agent loop result must not be null");
        }
    }
}
