package com.java.system.agent;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Slack transport 啟用時所需的 bot 與 Socket credentials
 */
@Validated
@ConfigurationProperties("agent.slack")
public record SlackAgentProperties(
        @NotBlank String appToken,
        @NotBlank String botToken,
        String botUserId) {
}
