package com.java.system.agent.api;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.git.service.GitService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepoControllerTest {

    @Test
    void should_run_clone_inside_reload_write_lock_when_cloning_repo() {
        GitService gitService = mock(GitService.class);
        AnalysisService analysisService = mock(AnalysisService.class);
        when(gitService.cloneRepository("repo-a", null)).thenReturn("cloned");
        when(analysisService.reloadRepoAfter(eq("repo-a"), any()))
                .thenAnswer(invocation -> invocation.<Supplier<String>>getArgument(1).get());
        RepoController controller = new RepoController(gitService, analysisService);

        ResponseEntity<String> response = controller.cloneRepo("repo-a", null);

        verify(gitService).cloneRepository("repo-a", null);
        verify(analysisService).reloadRepoAfter(eq("repo-a"), any());
        assertEquals("cloned - Cache reloaded", response.getBody());
    }

    @Test
    void should_run_pull_inside_reload_write_lock_when_pulling_repo() {
        GitService gitService = mock(GitService.class);
        AnalysisService analysisService = mock(AnalysisService.class);
        when(gitService.pullRepository("repo-a")).thenReturn("pulled");
        when(analysisService.reloadRepoAfter(eq("repo-a"), any()))
                .thenAnswer(invocation -> invocation.<Supplier<String>>getArgument(1).get());
        RepoController controller = new RepoController(gitService, analysisService);

        ResponseEntity<String> response = controller.pullRepo("repo-a");

        verify(gitService).pullRepository("repo-a");
        verify(analysisService).reloadRepoAfter(eq("repo-a"), any());
        assertEquals("pulled - Cache reloaded", response.getBody());
    }

    @Test
    void should_run_checkout_inside_reload_write_lock_when_checking_out_repo() {
        GitService gitService = mock(GitService.class);
        AnalysisService analysisService = mock(AnalysisService.class);
        when(gitService.checkoutBranch("repo-a", "dev")).thenReturn("ok");
        when(analysisService.reloadRepoAfter(eq("repo-a"), any()))
                .thenAnswer(invocation -> invocation.<Supplier<String>>getArgument(1).get());
        RepoController controller = new RepoController(gitService, analysisService);

        ResponseEntity<String> response = controller.checkoutRepo("repo-a", "dev");

        verify(gitService).checkoutBranch("repo-a", "dev");
        verify(analysisService).reloadRepoAfter(eq("repo-a"), any());
        assertEquals("ok - Cache reloaded", response.getBody());
    }
}
