package com.java.system.agent.api;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.git.service.GitService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepoControllerTest {

    @Test
    void cloneRepoReloadsAnalysisCaches() {
        GitService gitService = mock(GitService.class);
        AnalysisService analysisService = mock(AnalysisService.class);
        when(gitService.cloneRepository("repo-a", null)).thenReturn("cloned");
        RepoController controller = new RepoController(gitService, analysisService);

        controller.cloneRepo("repo-a", null);

        verify(analysisService).reloadRepo("repo-a");
    }

    @Test
    void checkoutRepoReloadsAnalysisCaches() {
        GitService gitService = mock(GitService.class);
        AnalysisService analysisService = mock(AnalysisService.class);
        when(gitService.checkoutBranch("repo-a", "dev")).thenReturn("ok");
        RepoController controller = new RepoController(gitService, analysisService);

        controller.checkoutRepo("repo-a", "dev");

        verify(analysisService).reloadRepo("repo-a");
    }
}
