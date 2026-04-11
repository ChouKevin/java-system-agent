package com.java.system.agent.git.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "git")
public class GitProperties {

    private String username;
    private String token;
    private Map<String, RepoConfig> repos = new HashMap<>();

    @Data
    public static class RepoConfig {
        private String url;
        private String defaultBranch;
        private String apiHost;
    }
}
