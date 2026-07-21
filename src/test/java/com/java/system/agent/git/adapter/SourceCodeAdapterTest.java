package com.java.system.agent.git.adapter;

import com.java.system.agent.analysis.exception.UnknownRepoException;
import com.java.system.agent.git.config.GitProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceCodeAdapterTest {

    @Test
    void sourceRoot_should_resolve_configured_repo_to_runtime_repos_directory() {
        GitProperties gitProperties = new GitProperties();
        gitProperties.getRepos().put("demo-repo", new GitProperties.RepoConfig());
        SourceCodeAdapter adapter = new SourceCodeAdapter(gitProperties);

        Path result = adapter.sourceRoot("demo-repo");

        assertThat(result).isEqualTo(Path.of("repos", "demo-repo"));
    }

    @Test
    void sourceRoot_should_reject_unknown_repo() {
        GitProperties gitProperties = new GitProperties();
        SourceCodeAdapter adapter = new SourceCodeAdapter(gitProperties);

        assertThatThrownBy(() -> adapter.sourceRoot("missing-repo"))
                .isInstanceOf(UnknownRepoException.class)
                .hasMessage("Unknown repository: missing-repo");
    }
}
