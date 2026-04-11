package com.java.system.agent.git.service;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.RepoDescriptor;
import com.java.system.agent.analysis.port.RepoRegistryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;

/** 啟動時預載所有 repo 的 metadata cache */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheWarmerService {

    private final RepoRegistryPort repoRegistryPort;
    private final AnalysisService analysisService;

    @PostConstruct
    public void warmupCache() {
        List<RepoDescriptor> repos = repoRegistryPort.all();
        log.info("Starting metadata cache preload for {} repositories...", repos.size());
        long startTime = System.currentTimeMillis();

        for (RepoDescriptor repo : repos) {
            try {
                log.info("Building cache for repository: {}", repo.repoId());
                analysisService.reloadRepo(repo.repoId());
                log.info("Cache built successfully for: {}", repo.repoId());
            } catch (Exception e) {
                log.warn("Failed to build cache for repository {}: {}",
                    repo.repoId(), e.getMessage(), e);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Cache preload completed in {}ms", duration);
    }
}
