package com.java.system.agent.git.adapter;

import com.java.system.agent.analysis.exception.UnknownRepoException;
import com.java.system.agent.analysis.port.SourceCodePort;
import com.java.system.agent.git.config.GitProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
class SourceCodeAdapter implements SourceCodePort {

    private final GitProperties gitProperties;

    SourceCodeAdapter(GitProperties gitProperties) {
        this.gitProperties = gitProperties;
    }

    @Override
    public Path sourceRoot(String repoId) {
        if (!gitProperties.getRepos().containsKey(repoId)) {
            throw new UnknownRepoException(repoId);
        }
        return Path.of("repos", repoId);
    }
}
