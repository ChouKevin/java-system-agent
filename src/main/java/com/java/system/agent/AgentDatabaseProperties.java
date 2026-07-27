package com.java.system.agent;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent runtime 在啟用 profile 時使用的 PostgreSQL 連線設定
 */
@ConfigurationProperties("spring.datasource")
public record AgentDatabaseProperties(
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password) {
}
