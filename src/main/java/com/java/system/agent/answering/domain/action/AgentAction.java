package com.java.system.agent.answering.domain.action;

/**
 * 模型唯一可以提出的下一步動作
 */
public sealed interface AgentAction permits QueryAction, AnswerAction, ClarifyAction {
}
