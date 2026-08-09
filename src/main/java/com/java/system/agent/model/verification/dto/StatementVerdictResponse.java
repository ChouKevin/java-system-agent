package com.java.system.agent.model.verification.dto;

import com.java.system.agent.answering.domain.answer.StatementVerdictStatus;

/**
 * 模型對單一回答 statement 的結構判定
 */
public record StatementVerdictResponse(String statementId, StatementVerdictStatus status, String description) {
}
