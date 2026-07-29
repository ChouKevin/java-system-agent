package com.java.system.agent.interaction.port.in;

/**
 * 關閉新的 inbox 與 delivery claim admission 的 inbound lifecycle contract
 */
public interface StopClaimingUseCase {

    void stopClaiming();
}
