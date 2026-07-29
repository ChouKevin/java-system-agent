package com.java.system.agent.capability.spi;

import com.java.system.agent.answering.port.out.CapabilityExecutionResult;

/**
 * 以 registration policy 識別的 capability 專屬型別化 read executor
 */
public interface CapabilityExecutor<E> {

    CapabilityExecutionResult execute(CapabilityExecutionContext context, E input);
}
