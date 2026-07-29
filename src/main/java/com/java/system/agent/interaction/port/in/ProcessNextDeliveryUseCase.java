package com.java.system.agent.interaction.port.in;

import com.java.system.agent.interaction.domain.delivery.DeliveryProcessingOutcome;

import java.time.Instant;
import java.util.Optional;

/**
 * 認領並處理至多一筆已到期 delivery 訊息的 inbound use-case contract
 */
public interface ProcessNextDeliveryUseCase {

    Optional<DeliveryProcessingOutcome> processNext(Instant now);
}
