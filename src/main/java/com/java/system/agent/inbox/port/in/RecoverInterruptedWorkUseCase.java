package com.java.system.agent.inbox.port.in;

import com.java.system.agent.inbox.domain.RecoverySummary;

import java.time.Instant;

/**
 * 在啟動時復原中斷的 inbox 與 delivery claim 的 inbound use-case contract
 */
public interface RecoverInterruptedWorkUseCase {

    RecoverySummary recoverInterrupted(Instant recoveredAt);
}
