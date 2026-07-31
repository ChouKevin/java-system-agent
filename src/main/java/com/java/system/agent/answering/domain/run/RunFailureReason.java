package com.java.system.agent.answering.domain.run;

/**
 * answering 在無法安全完成分析時持久化的內部 terminal failure 原因
 */
public enum RunFailureReason {
    PLANNING_TOOL_CONTRACT,
    HTTP_MUTATION_CONTRACT
}
