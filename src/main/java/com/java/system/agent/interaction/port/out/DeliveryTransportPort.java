package com.java.system.agent.interaction.port.out;

import com.java.system.agent.interaction.domain.delivery.DeliveryMessage;
import com.java.system.agent.interaction.domain.delivery.DeliveryTransportResult;

import java.time.Instant;

/**
 * 將 transport-neutral delivery 傳給外部 provider 的外部邊界
 */
public interface DeliveryTransportPort {

    DeliveryTransportResult deliver(DeliveryMessage message, Instant now);
}
