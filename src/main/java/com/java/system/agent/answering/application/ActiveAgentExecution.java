package com.java.system.agent.answering.application;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 主迴圈繼續執行一個 active agent run 所需的不可變上下文
 */
record ActiveAgentExecution(
        AgentRunState state,
        SessionHistory sessionHistory,
        List<CapabilityPolicy> capabilityCatalog,
        List<RepositoryDescriptor> repositoryCatalog,
        Set<RepositoryId> catalogRepositoryIds,
        int attemptSequence,
        Optional<String> latestRejection) {

    ActiveAgentExecution {
        Objects.requireNonNull(state, "agent run state must not be null");
        Objects.requireNonNull(sessionHistory, "session history must not be null");
        capabilityCatalog = List.copyOf(Objects.requireNonNull(capabilityCatalog, "capability catalog must not be null"));
        repositoryCatalog = List.copyOf(Objects.requireNonNull(repositoryCatalog, "repository catalog must not be null"));
        catalogRepositoryIds = Set.copyOf(Objects.requireNonNull(
                catalogRepositoryIds, "catalog repository IDs must not be null"));
        Objects.requireNonNull(latestRejection, "latest rejection must not be null");
    }
}
