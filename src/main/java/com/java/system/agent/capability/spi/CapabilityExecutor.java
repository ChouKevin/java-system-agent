package com.java.system.agent.capability.spi;

import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;

/**
 * 一個已宣告 capability 的外部執行器 SPI，由 capability dispatcher 以精確 descriptor 路由
 */
public interface CapabilityExecutor {

    CapabilityPolicy capability();

    CapabilityExecutionResult execute(CapabilityInvocation invocation);
}
