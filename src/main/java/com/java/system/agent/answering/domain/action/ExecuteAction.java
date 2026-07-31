package com.java.system.agent.answering.domain.action;

import java.util.Objects;
import java.util.Optional;

/**
 * 模型提出且尚未經 runtime 驗證的外部 HTTP mutation intent
 */
public record ExecuteAction(
        ExternalHttpMethod method,
        String targetUrl,
        Optional<String> jsonBody,
        String rationale) implements AgentAction {

    public ExecuteAction {
        Objects.requireNonNull(method, "HTTP mutation method must not be null");
        Objects.requireNonNull(targetUrl, "HTTP mutation target URL must not be null");
        Objects.requireNonNull(jsonBody, "HTTP mutation JSON body container must not be null");
        Objects.requireNonNull(rationale, "HTTP mutation rationale must not be null");
        if (targetUrl.isBlank() || rationale.isBlank()) {
            throw new IllegalArgumentException("HTTP mutation target URL and rationale must not be blank");
        }
    }
}
