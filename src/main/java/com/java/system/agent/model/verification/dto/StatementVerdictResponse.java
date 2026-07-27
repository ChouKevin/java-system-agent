package com.java.system.agent.model.verification.dto;

/**
 * 模型對單一回答 statement 的結構判定
 */
public record StatementVerdictResponse(String statementId, String status, String description) {
}
