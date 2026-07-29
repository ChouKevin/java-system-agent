package com.java.system.agent.interaction.application;

import com.java.system.agent.interaction.domain.delivery.DeliveryStatus;
import com.java.system.agent.interaction.port.in.AgentOperationsSnapshot;
import com.java.system.agent.interaction.port.out.AgentOperationsPort;
import com.java.system.agent.interaction.port.out.DurableAgentOperationsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent operations read boundary 將 durable 年齡與 composition supplied worker 狀態合併的測試
 */
class AgentOperationsApplicationServiceTest {

    @Test
    void combinesDurableQueueAgesWithTheSuppliedWorkerStateAtTheRequestedInstant() {
        Instant observedAt = Instant.parse("2030-07-28T08:00:00Z");
        AgentOperationsPort port = now -> new DurableAgentOperationsSnapshot(
                now,
                Optional.of(Duration.ofMinutes(3)),
                Map.of(DeliveryStatus.PENDING, Optional.of(Duration.ofMinutes(2))));
        AgentOperationsApplicationService service = new AgentOperationsApplicationService(
                port, () -> AgentOperationsSnapshot.WorkerState.BUSY);

        AgentOperationsSnapshot snapshot = service.read(observedAt);

        assertThat(snapshot.observedAt()).isEqualTo(observedAt);
        assertThat(snapshot.oldestEligibleInboxAge()).contains(Duration.ofMinutes(3));
        assertThat(snapshot.oldestDeliveryAgeByStatus().get(DeliveryStatus.PENDING))
                .contains(Duration.ofMinutes(2));
        assertThat(snapshot.workerState()).isEqualTo(AgentOperationsSnapshot.WorkerState.BUSY);
    }
}
