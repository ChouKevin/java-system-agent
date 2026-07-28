package com.java.system.agent.inbox.port.out;

import com.java.system.agent.inbox.domain.delivery.DeliveryMessage;
import com.java.system.agent.inbox.domain.delivery.DeliveryTransportResult;

import java.time.Instant;

/**
 * 將 transport-neutral delivery 傳給外部 provider 的外部邊界
 */
public interface DeliveryTransportPort {

    DeliveryTransportResult deliver(DeliveryMessage message, Instant now);
}
