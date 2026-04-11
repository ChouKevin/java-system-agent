package com.java.system.agent.git.adapter;

import com.java.system.agent.common.port.RepoApiHostProvider;
import com.java.system.agent.git.config.GitProperties;
import com.java.system.agent.git.config.GitProperties.RepoConfig;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Component
public class RepoApiHostAdapter implements RepoApiHostProvider {

    private final GitProperties gitProperties;

    RepoApiHostAdapter(GitProperties gitProperties) {
        this.gitProperties = gitProperties;
    }

    @Override
    public Optional<String> getApiHost(String repoId) {
        RepoConfig repoConfig = gitProperties.getRepos().get(repoId);
        if (repoConfig == null || !StringUtils.hasText(repoConfig.getApiHost())) {
            return Optional.empty();
        }
        return Optional.of(repoConfig.getApiHost());
    }
}
