package com.java.system.agent.git.service;

import com.java.system.agent.git.config.GitProperties;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;

@Service("gitModuleGitService")
@RequiredArgsConstructor
public class GitService {

    public static final String DIR = "repos";

    private final GitProperties gitProperties;

    /** Clones a repo; returns the local directory path. */
    public String cloneRepository(String repo, String branch) {
        String url = resolveUrl(repo);
        File targetDir = repoDir(repo);
        try {
            File reposDir = new File(DIR);
            if (!reposDir.exists()) {
                reposDir.mkdirs();
            }

            if (targetDir.exists() && new File(targetDir, ".git").exists()) {
                return "Repository '" + repo + "' is already cloned at: " + targetDir.getAbsolutePath()
                        + ". Use /pull-repo to fetch latest changes or /checkout-repo to switch branches.";
            }

            CloneCommand cloneCommand = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(targetDir);

            String targetBranch = StringUtils.hasText(branch)
                    ? branch
                    : resolveDefaultBranch(repo);
            if (StringUtils.hasText(targetBranch)) {
                cloneCommand.setBranch(targetBranch);
            }

            cloneCommand.setCredentialsProvider(getCredentialsProvider());
            cloneCommand.call();

            return targetDir.getAbsolutePath();
        } catch (GitAPIException e) {
            throw new RuntimeException("Failed to clone repository: " + url, e);
        }
    }

    /** Pulls latest changes from remote. */
    public String pullRepository(String repo) {
        File localDir = repoDir(repo);
        try (Git git = Git.open(localDir)) {
            return git.pull()
                    .setCredentialsProvider(getCredentialsProvider())
                    .call()
                    .toString();
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException("Failed to pull repository: " + repo, e);
        }
    }

    /** Checks out a branch, creating a local tracking branch if needed. */
    public String checkoutBranch(String repo, String branch) {
        File localDir = repoDir(repo);
        try (Git git = Git.open(localDir)) {
            boolean localBranchExists = git.getRepository().findRef("refs/heads/" + branch) != null;

            CheckoutCommand checkout = git.checkout().setName(branch);
            if (!localBranchExists) {
                // Mimic native `git checkout <branch>`: create local branch tracking origin/<branch>
                checkout.setCreateBranch(true)
                        .setStartPoint("origin/" + branch)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK);
            }
            checkout.call();
            return "Successfully checked out to " + branch;
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException("Failed to checkout branch " + branch + " in " + repo, e);
        }
    }

    /** Returns the current branch name. */
    public String getCurrentBranch(String repo) {
        File localDir = repoDir(repo);
        try (Git git = Git.open(localDir)) {
            return git.getRepository().getBranch();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get current branch for: " + repo, e);
        }
    }

    /** Returns repos/{repoId} directory. */
    public File repoDir(String repo) {
        return new File(DIR, repo);
    }

    private String resolveUrl(String repo) {
        GitProperties.RepoConfig config = gitProperties.getRepos().get(repo);
        if (config == null || !StringUtils.hasText(config.getUrl())) {
            throw new IllegalStateException("No URL configured for repo: " + repo);
        }
        return config.getUrl();
    }

    private String resolveDefaultBranch(String repo) {
        GitProperties.RepoConfig config = gitProperties.getRepos().get(repo);
        return config != null ? config.getDefaultBranch() : null;
    }

    private CredentialsProvider getCredentialsProvider() {
        String u = gitProperties.getUsername();
        String p = gitProperties.getToken();
        if (u != null && !u.isEmpty() && p != null && !p.isEmpty()) {
            return new UsernamePasswordCredentialsProvider(u, p);
        }
        return null;
    }
}
