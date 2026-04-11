package com.java.system.agent.analysis.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves all Java source roots for a repository.
 * Supports both single-module and multi-module Maven projects.
 */
@Slf4j
@Component
public class SourceRootResolver {

    private static final String SRC_MAIN_JAVA = "src/main/java";
    private static final String SRC_MAIN_RESOURCES = "src/main/resources";
    private static final Pattern MODULE_PATTERN = Pattern.compile("<module>\\s*([^<]+)\\s*</module>");

    private final ConcurrentHashMap<String, List<Path>> sourceRootCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Path>> resourceRootCache = new ConcurrentHashMap<>();

    /** Returns all Java source roots (src/main/java) for the given repo. */
    public List<Path> resolveSourceRoots(Path repoRoot) {
        return sourceRootCache.computeIfAbsent(repoRoot.toString(), k -> doResolveRoots(repoRoot, SRC_MAIN_JAVA));
    }

    /** Returns all resource roots (src/main/resources) for the given repo. */
    public List<Path> resolveResourceRoots(Path repoRoot) {
        return resourceRootCache.computeIfAbsent(repoRoot.toString(), k -> doResolveRoots(repoRoot, SRC_MAIN_RESOURCES));
    }

    /** Evicts cached roots for the given repo. */
    public void invalidate(Path repoRoot) {
        sourceRootCache.remove(repoRoot.toString());
        resourceRootCache.remove(repoRoot.toString());
    }

    private List<Path> doResolveRoots(Path repoRoot, String suffix) {
        List<String> modules = parseModules(repoRoot);

        if (CollectionUtils.isEmpty(modules)) {
            // Single-module project
            Path root = repoRoot.resolve(suffix);
            return Files.exists(root) ? List.of(root) : List.of();
        }

        // Multi-module project
        List<Path> roots = new ArrayList<>();
        for (String module : modules) {
            Path root = repoRoot.resolve(module).resolve(suffix);
            if (Files.exists(root)) {
                roots.add(root);
            }
        }
        log.info("Resolved {} {} roots for multi-module repo: {}", roots.size(), suffix, repoRoot);
        return List.copyOf(roots);
    }

    /** Parses modules from pom.xml. Returns empty list if no pom.xml or no modules. */
    private List<String> parseModules(Path repoRoot) {
        Path pomFile = repoRoot.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            return List.of();
        }

        try {
            String content = Files.readString(pomFile);
            // Quick check: if packaging is not "pom", it's not a parent pom
            if (!content.contains("<packaging>pom</packaging>")) {
                return List.of();
            }

            List<String> modules = new ArrayList<>();
            Matcher matcher = MODULE_PATTERN.matcher(content);
            while (matcher.find()) {
                modules.add(matcher.group(1).trim());
            }
            return modules;
        } catch (IOException e) {
            log.warn("Failed to read pom.xml for module detection: {}", pomFile, e);
            return List.of();
        }
    }
}
