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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 管理每個 Repo 的 JavaParser 實例（含 Symbol Solver），提供獨立解析上下文。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectParserService {

    private final SourceRootResolver sourceRootResolver;

    // Key: Repo 根目錄絕對路徑
    private final ConcurrentHashMap<String, JavaParser> parserCache = new ConcurrentHashMap<>();

    /** 取得或建立指定 Repo 的 JavaParser 實例 */
    public JavaParser getOrCreateParser(Path repoRootPath) {
        String cacheKey = repoRootPath.toString();

        return parserCache.computeIfAbsent(cacheKey, key -> {
            log.info("Creating new JavaParser instance for repository: {}", repoRootPath);

            try {
                CombinedTypeSolver typeSolver = new CombinedTypeSolver();

                List<Path> sourceRoots = sourceRootResolver.resolveSourceRoots(repoRootPath);
                if (sourceRoots.isEmpty()) {
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
                config.setSymbolResolver(symbolSolver);

                JavaParser parser = new JavaParser(config);
                log.info("Successfully created JavaParser with {} source roots for: {}",
                        sourceRoots.size(), repoRootPath);

                return parser;
            } catch (Exception e) {
                log.error("Failed to create JavaParser for {}: {}", repoRootPath, e.getMessage(), e);
                return new JavaParser();
            }
        });
    }

    /** 清除指定 Repo 的 Parser 快取 */
    public void invalidate(Path repoRootPath) {
        String cacheKey = repoRootPath.toString();
        JavaParser removed = parserCache.remove(cacheKey);
        if (removed != null) {
            log.info("Invalidated parser cache for: {}", repoRootPath);
        }
    }

    /** 清除所有 Parser 快取 */
    public void invalidateAll() {
        int size = parserCache.size();
        parserCache.clear();
        log.info("Cleared all parser cache, removed {} entries", size);
    }

    /** 取得當前快取數量 */
    public int getCacheSize() {
        return parserCache.size();
    }
}
