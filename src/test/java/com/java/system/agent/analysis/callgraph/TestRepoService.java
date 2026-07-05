package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.MethodRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Test helper that provides access to the internal CallGraph tree structure
 * for use in integration tests. Uses JavaCallGraphAnalyzer directly to bypass
 * the AnalysisService facade (which returns FlattenedCallGraph).
 */
public class TestRepoService {

    private final JavaCallGraphAnalyzer javaCallGraphAnalyzer;
    private final Path repoRoot;

    public TestRepoService(Path repoRoot,
                           EntryPointCacheService entryPointCacheService,
                           JavaCallGraphAnalyzer javaCallGraphAnalyzer) {
        this.javaCallGraphAnalyzer = javaCallGraphAnalyzer;
        this.repoRoot = repoRoot;
    }

    public String repoId() {
        return "test-repo";
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public String description() {
        return "Test Repo for Call Graph verification";
    }

    /**
     * Returns the raw CallGraph tree for test assertions.
     * Uses JavaCallGraphAnalyzer directly to get the internal tree structure.
     */
    public CallGraph readJavaSource(MethodRef method) {
        String relativeFilePath = toRelativePath(method);
        return javaCallGraphAnalyzer.analyze(repoRoot(), relativeFilePath, method.methodSignature());
    }

    /**
     * Returns the flattened call graph for test assertions.
     */
    public FlattenedCallGraph readFlattened(MethodRef method) {
        String relativeFilePath = toRelativePath(method);
        return javaCallGraphAnalyzer.analyzeFlattened(repoRoot(), relativeFilePath, method.methodSignature());
    }

    private String toRelativePath(MethodRef method) {
        Path packagePath = Paths.get(method.packageName().replace('.', '/'), method.className() + ".java");
        try (Stream<Path> paths = Files.walk(repoRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.endsWith(packagePath))
                    .findFirst()
                    .map(path -> repoRoot.relativize(path).toString())
                    .orElseGet(() -> defaultRelativePath(method));
        } catch (IOException e) {
            return defaultRelativePath(method);
        }
    }

    private String defaultRelativePath(MethodRef method) {
        return "src/main/java/"
            + method.packageName().replace('.', '/') + "/"
            + method.className() + ".java";
    }
}
