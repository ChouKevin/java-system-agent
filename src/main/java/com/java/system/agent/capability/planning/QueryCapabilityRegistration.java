package com.java.system.agent.capability.planning;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;

/**
 * QUERY capability 共用的 execution metadata，供 planning 發行策略與 dispatcher 分別使用
 */
public sealed interface QueryCapabilityRegistration<E> permits QueryPlanningToolRegistration {

    CapabilityPolicy policy();

    Class<E> executionInputType();

    CapabilityExecutor<E> executor();
}
