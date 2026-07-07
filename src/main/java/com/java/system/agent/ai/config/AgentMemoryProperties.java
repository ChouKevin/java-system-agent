package com.java.system.agent.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent.memory")
public record AgentMemoryProperties(@DefaultValue("500") int maxConversations) {
}
