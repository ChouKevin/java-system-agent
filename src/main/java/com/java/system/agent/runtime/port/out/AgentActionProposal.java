package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.action.AgentAction;

import java.util.Objects;

/**
 * 外部 Agent adapter 對下一個動作的可解析或不可解析回應
 */
public sealed interface AgentActionProposal permits AgentActionProposal.Proposed, AgentActionProposal.Malformed {

    /**
     * 已成功解析為 runtime 可驗證動作的回應
     */
    record Proposed(AgentAction action) implements AgentActionProposal {
        public Proposed {
            Objects.requireNonNull(action, "proposed agent action must not be null");
        }
    }

    /**
     * 無法解析為 runtime 動作的外部回應
     */
    record Malformed(String description) implements AgentActionProposal {
        public Malformed {
            Objects.requireNonNull(description, "malformed proposal description must not be null");
            if (description.isBlank()) {
                throw new IllegalArgumentException("malformed proposal description must not be blank");
            }
        }
    }
}
