package com.java.system.agent.model.verification.dto;

import java.util.List;

/**
 * 模型回答 verifier 的完整結構輸出
 */
public record AnswerVerdictResponse(String disposition, List<StatementVerdictResponse> statementVerdicts,
                                    List<String> unaddressedParts, List<String> blockingUncertainties,
                                    List<String> rejectionReasons) {
}
