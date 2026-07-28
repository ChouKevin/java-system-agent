package com.java.system.agent.inbox.application;

import com.java.system.agent.inbox.port.in.AgentOperationsSnapshot;
import com.java.system.agent.inbox.port.in.ReadAgentOperationsUseCase;
import com.java.system.agent.inbox.port.out.AgentOperationsPort;
import com.java.system.agent.inbox.port.out.DurableAgentOperationsSnapshot;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 將 durable queue 年齡與 composition 提供的 worker 狀態合併的 framework-free application service
 */
public final class AgentOperationsApplicationService implements ReadAgentOperationsUseCase {

    private final AgentOperationsPort operationsPort;
    private final Supplier<AgentOperationsSnapshot.WorkerState> workerState;

    public AgentOperationsApplicationService(
            AgentOperationsPort operationsPort,
            Supplier<AgentOperationsSnapshot.WorkerState> workerState) {
        this.operationsPort = Objects.requireNonNull(operationsPort, "agent operations port must not be null");
        this.workerState = Objects.requireNonNull(workerState, "worker state supplier must not be null");
    }

    @Override
    public AgentOperationsSnapshot read(Instant observedAt) {
        Objects.requireNonNull(observedAt, "operations observation time must not be null");
        DurableAgentOperationsSnapshot durableSnapshot = operationsPort.readDurableOperations(observedAt);
        return new AgentOperationsSnapshot(
                durableSnapshot.observedAt(),
                durableSnapshot.oldestEligibleInboxAge(),
                durableSnapshot.oldestDeliveryAgeByStatus(),
                Objects.requireNonNull(workerState.get(), "worker state must not be null"));
    }
}
