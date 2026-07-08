package com.java.system.agent.api;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.RepoDescriptor;
import com.java.system.agent.git.service.GitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@Validated
@RequiredArgsConstructor
@Tag(name = "Git Operations", description = "API for cloning and updating Git repositories")
class RepoController {

    private final GitService gitService;
    private final AnalysisService analysisService;

    @GetMapping("/git/repos")
    @Operation(summary = "List configured repositories",
               description = "Returns all repositories configured in application.yml.")
    public ResponseEntity<List<RepoDescriptor>> listRepos() {
        return ResponseEntity.ok(analysisService.allRepos());
    }

    @PostMapping("/git/clone-repo/{repo}")
    @Operation(summary = "Clone a repository", description = "Clones a remote Git repository to the local 'repos' directory. URL is resolved from application.yml by repo id.")
    public ResponseEntity<String> cloneRepo(@PathVariable String repo,
                                            @RequestParam(required = false) String branch) {
        String result = gitService.cloneRepository(repo, branch);
        analysisService.reloadRepo(repo);
        return ResponseEntity.ok(result + " - Cache reloaded");
    }

    @PostMapping("/git/pull-repo/{repo}")
    @Operation(summary = "Pull a repository", description = "Pulls the latest changes for an existing repository.")
    public ResponseEntity<String> pullRepo(@PathVariable String repo) {
        String result = gitService.pullRepository(repo);
        analysisService.reloadRepo(repo);

        return ResponseEntity.ok(result + " - Cache reloaded");
    }

    @PostMapping("/git/checkout-repo/{repo}")
    @Operation(summary = "Checkout a branch", description = "Checkouts a specific branch or commit in a repository.")
    public ResponseEntity<String> checkoutRepo(@PathVariable String repo,
                                               @RequestParam @NotBlank(message = "branch must not be blank") String branch) {
        String result = gitService.checkoutBranch(repo, branch);
        analysisService.reloadRepo(repo);
        return ResponseEntity.ok(result + " - Cache reloaded");
    }

    @GetMapping("/git/current-branch/{repo}")
    @Operation(summary = "Get current branch", description = "Returns the currently checked-out branch name for a repository.")
    public ResponseEntity<String> currentBranch(@PathVariable String repo) {
        return ResponseEntity.ok(gitService.getCurrentBranch(repo));
    }
}
