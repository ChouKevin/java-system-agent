package com.java.system.agent.model.action.dto;

import java.util.List;
import java.util.Map;

/**
 * QUERY action 的模型資料形狀
 */
public record QueryResponse(String capabilityHandle, List<String> candidateHandles, String questionToResolve,
                            Map<String, String> arguments, String rationale) {
}
