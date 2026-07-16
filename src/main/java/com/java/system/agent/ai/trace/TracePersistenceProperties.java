package com.java.system.agent.ai.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent.trace")
public record TracePersistenceProperties(
        @DefaultValue("false") boolean persistenceEnabled) {
}
