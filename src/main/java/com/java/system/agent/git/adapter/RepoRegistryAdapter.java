package com.java.system.agent.git.adapter;

import com.java.system.agent.analysis.model.RepoDescriptor;
import com.java.system.agent.analysis.port.RepoDocPort;
import com.java.system.agent.analysis.port.RepoRegistryPort;
import com.java.system.agent.analysis.port.SourceCodePort;
import com.java.system.agent.git.config.GitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RepoRegistryAdapter implements RepoRegistryPort {

    private final SourceCodePort sourceCodePort;
    private final RepoDocPort repoDocPort;
    private final GitProperties gitProperties;

    @Override
    public List<RepoDescriptor> all() {
        return gitProperties.getRepos().keySet().stream().sorted()
                .map(repoId -> new RepoDescriptor(
                        repoId,
                        repoId,
                        repoDocPort.readSummary(repoId),
                        sourceCodePort.sourceRoot(repoId)))
                .toList();
    }
}
