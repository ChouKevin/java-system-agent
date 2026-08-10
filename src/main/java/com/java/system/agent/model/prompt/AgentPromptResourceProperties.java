package com.java.system.agent.model.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent model prompt 資源位置的 runtime composition 設定
 */
@ConfigurationProperties("agent.model.prompts")
public record AgentPromptResourceProperties(
        @DefaultValue("classpath:/prompts/action/system.md") String actionSystem,
        @DefaultValue("classpath:/prompts/action/context.st") String actionContext,
        @DefaultValue("classpath:/prompts/verification/system.md") String verificationSystem,
        @DefaultValue("classpath:/prompts/verification/context.st") String verificationContext,
        @DefaultValue("classpath:/prompts/tools/") String toolRoot,
        @DefaultValue("classpath:/prompts/evidence-requirements.yml") String evidenceRequirements) {
}
