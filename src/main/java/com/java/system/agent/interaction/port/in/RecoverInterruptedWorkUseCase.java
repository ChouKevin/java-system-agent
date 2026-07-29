package com.java.system.agent.interaction.port.in;

import com.java.system.agent.interaction.domain.RecoverySummary;

import java.time.Instant;

/**
 * 在啟動時復原中斷的 inbox 與 delivery claim 的 inbound use-case contract
 */
public interface RecoverInterruptedWorkUseCase {

    RecoverySummary recoverInterrupted(Instant recoveredAt);
}
