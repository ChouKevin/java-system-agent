package com.java.system.agent.model.action.dto;

import java.util.List;

/**
 * 回答單一 statement 的模型資料形狀
 */
public record AnswerStatementResponse(String statementId, String type, String text, String claimId,
                                      List<String> citationHandles, List<String> observationIds) {
}
