package com.java.system.agent.runtime.port.out;

/**
 * 取得模型提出的下一個 Agent 動作的外部邊界
 */
public interface AgentActionPort {

    AgentActionProposal nextAction(AgentPromptContext context);
}
