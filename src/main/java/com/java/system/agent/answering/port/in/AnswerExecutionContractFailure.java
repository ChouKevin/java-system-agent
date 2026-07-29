package com.java.system.agent.answering.port.in;

/**
 * inbox 可安全分類且不重試的回答執行契約失敗種類
 */
public enum AnswerExecutionContractFailure {
    GENERAL_INTEGRATION_CONTRACT,
    PLANNING_TOOL_CONTRACT
}
