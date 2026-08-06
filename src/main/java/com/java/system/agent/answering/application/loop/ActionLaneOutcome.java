package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.domain.run.AgentRunState;

import java.util.Objects;
import java.util.Optional;

/**
 * 單一 action lane 推進後交回主迴圈的結果
 */
sealed interface ActionLaneOutcome permits ActionLaneOutcome.Continue, ActionLaneOutcome.Terminal {

    record Continue(AgentRunState state, int attemptSequence, Optional<String> latestRejection)
            implements ActionLaneOutcome {

        public Continue {
            Objects.requireNonNull(state, "agent run state must not be null");
            Objects.requireNonNull(latestRejection, "latest rejection must not be null");
        }
    }

    record Terminal(AgentLoopResult result) implements ActionLaneOutcome {

        public Terminal {
            Objects.requireNonNull(result, "agent loop result must not be null");
        }
    }
}
