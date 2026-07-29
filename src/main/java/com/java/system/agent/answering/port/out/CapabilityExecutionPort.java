package com.java.system.agent.answering.port.out;

/**
 * 執行 answering 已驗證 Agent 語意查詢的外部邊界
 */
public interface CapabilityExecutionPort {

    CapabilityExecutionResult execute(CapabilityInvocation invocation);
}
