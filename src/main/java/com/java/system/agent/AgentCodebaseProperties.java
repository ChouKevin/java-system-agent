package com.java.system.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Java Semantic Service HTTP adapter 在啟用 profile 時使用的連線設定
 */
@Validated
@ConfigurationProperties("agent.codebase")
public record AgentCodebaseProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiToken,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
