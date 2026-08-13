package com.java.system.agent;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Agent runtime 在 planning 前固定使用的唯一 repository scope 設定
 */
@Validated
@ConfigurationProperties("agent.repository-scope")
public record AgentRepositoryScopeProperties(@NotBlank String repositoryId) {
}
