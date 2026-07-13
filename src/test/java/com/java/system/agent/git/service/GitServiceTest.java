package com.java.system.agent.git.service;

import com.java.system.agent.analysis.exception.UnknownRepoException;
import com.java.system.agent.git.config.GitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GitServiceTest {

    private GitService gitService;

    @BeforeEach
    void setUp() {
        GitProperties gitProperties = new GitProperties();
        gitService = new GitService(gitProperties);
    }

    @Test
    void should_throw_UnknownRepoException_when_cloning_unknown_repo() {
        assertThrows(UnknownRepoException.class, () -> gitService.cloneRepository("ghost-repo", null));
    }

    @Test
    void should_throw_UnknownRepoException_when_pulling_unknown_repo() {
        assertThrows(UnknownRepoException.class, () -> gitService.pullRepository("ghost-repo"));
    }

    @Test
    void should_throw_UnknownRepoException_when_checking_out_unknown_repo() {
        assertThrows(UnknownRepoException.class,
                () -> gitService.checkoutBranch("ghost-repo", "main"));
    }

    @Test
    void should_throw_UnknownRepoException_when_reading_branch_of_unknown_repo() {
        assertThrows(UnknownRepoException.class, () -> gitService.getCurrentBranch("ghost-repo"));
    }
}
