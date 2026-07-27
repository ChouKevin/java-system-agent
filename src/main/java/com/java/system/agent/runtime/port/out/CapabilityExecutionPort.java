package com.java.system.agent.runtime.port.out;

/**
 * 執行 runtime 已驗證 Agent 語意查詢的外部邊界
 */
public interface CapabilityExecutionPort {

    CapabilityExecutionResult execute(CapabilityInvocation invocation);
}
