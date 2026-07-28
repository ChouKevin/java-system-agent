package com.java.system.agent.model.action.dto;

/**
 * 模型 action 回應的單一 discriminated envelope
 */
public record AgentActionResponse(ActionResponseType type, AnswerResponse answer, ClarifyResponse clarify) {
}
