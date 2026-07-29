package com.java.system.agent.interaction.port.in;

import java.time.Instant;

/**
 * 讀取 Agent durable queue 與 in-process worker 的 bounded operations snapshot
 */
public interface ReadAgentOperationsUseCase {

    AgentOperationsSnapshot read(Instant observedAt);
}
