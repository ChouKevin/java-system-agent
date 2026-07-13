package com.java.system.agent.analysis.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理每個 Repo 的 ParserConfiguration（含 Symbol Solver）快取
 * JavaParser 本身非執行緒安全，因此每次呼叫都建立新的 JavaParser 實例
 * 昂貴的 type solver 與 symbol solver 掛在 ParserConfiguration 上共用
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectParserService {

    private final SourceRootResolver sourceRootResolver;

    private final ConcurrentHashMap<String, ParserConfiguration> configCache = new ConcurrentHashMap<>();

    /** 為指定 Repo 建立新的 JavaParser 實例，共用快取的 ParserConfiguration */
    public JavaParser createParser(Path repoRootPath) {
        return new JavaParser(getOrCreateConfiguration(repoRootPath));
    }

    private ParserConfiguration getOrCreateConfiguration(Path repoRootPath) {
        String cacheKey = repoRootPath.toString();
        return configCache.computeIfAbsent(cacheKey, key -> buildConfiguration(repoRootPath));
    }

    private ParserConfiguration buildConfiguration(Path repoRootPath) {
        log.info("Creating new ParserConfiguration for repository: {}", repoRootPath);
        try {
            CombinedTypeSolver typeSolver = new CombinedTypeSolver();

            List<Path> sourceRoots = sourceRootResolver.resolveSourceRoots(repoRootPath);
            if (CollectionUtils.isEmpty(sourceRoots)) {
                log.warn("No source roots found for: {}, Symbol resolution may be limited", repoRootPath);
            } else {
                for (Path sourceRoot : sourceRoots) {
                    typeSolver.add(new JavaParserTypeSolver(sourceRoot));
                    log.debug("Added JavaParserTypeSolver for source root: {}", sourceRoot);
                }
            }

            typeSolver.add(new ReflectionTypeSolver());

            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
            ParserConfiguration config = new ParserConfiguration();
            config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
            config.setSymbolResolver(symbolSolver);

            log.info("Successfully created ParserConfiguration with {} source roots for: {}",
                    sourceRoots.size(), repoRootPath);
            return config;
        } catch (Exception e) {
            log.error("Failed to create ParserConfiguration for {}: {}", repoRootPath, e.getMessage(), e);
            return new ParserConfiguration();
        }
    }

    /** 清除指定 Repo 的 Configuration 快取 */
    public void invalidate(Path repoRootPath) {
        String cacheKey = repoRootPath.toString();
        ParserConfiguration removed = configCache.remove(cacheKey);
        if (Objects.nonNull(removed)) {
            log.info("Invalidated parser configuration cache for: {}", repoRootPath);
        }
    }

    /** 清除所有 Configuration 快取 */
    public void invalidateAll() {
        int size = configCache.size();
        configCache.clear();
        log.info("Cleared all parser configuration cache, removed {} entries", size);
    }

    /** 取得當前快取數量 */
    public int getCacheSize() {
        return configCache.size();
    }
}
