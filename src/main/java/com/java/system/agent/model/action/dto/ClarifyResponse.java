package com.java.system.agent.model.action.dto;

import java.util.List;

/**
 * CLARIFY action 的模型資料形狀
 */
public record ClarifyResponse(String question, List<String> candidateHandles, String reason) {
}
