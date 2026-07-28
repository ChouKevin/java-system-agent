package com.java.system.agent.inbox.port.out;

import java.time.Instant;

/**
 * 讀取 durable inbox 與 delivery queue 年齡的基礎設施邊界
 */
public interface AgentOperationsPort {

    DurableAgentOperationsSnapshot readDurableOperations(Instant observedAt);
}
