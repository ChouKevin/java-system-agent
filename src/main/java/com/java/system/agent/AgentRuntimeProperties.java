package com.java.system.agent;

import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Agent runtime 在啟用 profile 時使用的回答驗證設定
 */
@Validated
@ConfigurationProperties("agent.answer-verification")
public record AgentRuntimeProperties(@NotNull AnswerVerificationMode mode) {
}
