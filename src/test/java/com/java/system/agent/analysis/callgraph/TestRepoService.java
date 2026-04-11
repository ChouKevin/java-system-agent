package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.MethodRef;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test helper that provides access to the internal CallGraph tree structure
 * for use in integration tests. Uses JavaCallGraphAnalyzer directly to bypass
 * the AnalysisService facade (which returns FlattenedCallGraph).
 */
public class TestRepoService {
    private final JavaCallGraphAnalyzer javaCallGraphAnalyzer;

    public TestRepoService(EntryPointCacheService entryPointCacheService, JavaCallGraphAnalyzer javaCallGraphAnalyzer) {
        this.javaCallGraphAnalyzer = javaCallGraphAnalyzer;
    }

    public String repoId() {
        return "test-repo";
    }

    public Path repoRoot() {
        return Paths.get("repos/test");
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
        return "src/main/java/"
            + method.packageName().replace('.', '/') + "/"
            + method.className() + ".java";
    }
}
