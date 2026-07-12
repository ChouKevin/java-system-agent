package com.java.system.agent.analysis;

import com.java.system.agent.analysis.model.ApiRef;
import com.java.system.agent.analysis.model.AnalysisErrorCode;
import com.java.system.agent.analysis.model.AnalysisMetadata;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.EntryPointClass;
import com.java.system.agent.analysis.model.EntryPointType;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.RepoDescriptor;
import com.java.system.agent.analysis.exception.UnknownRepoException;
import com.java.system.agent.analysis.port.RepoRegistryPort;
import com.java.system.agent.analysis.port.SourceCodePort;
import com.java.system.agent.analysis.trie.ApiEntryPointRef;
import com.java.system.agent.analysis.trie.ApiTrieService;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.callgraph.JavaCallGraphAnalyzer;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.type.ClassMetadataService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Public facade for the analysis module.
 * All cross-module access to analysis capabilities must go through this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final JavaCallGraphAnalyzer javaCallGraphAnalyzer;
    private final EntryPointCacheService entryPointCacheService;
    private final ApiTrieService apiTrieService;
    private final ClassMetadataService classMetadataService;
    private final ProjectParserService projectParserService;
    private final SourceCodePort sourceCodePort;
    private final RepoRegistryPort repoRegistryPort;
    private final SourceRootResolver sourceRootResolver;
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> repoLocks = new ConcurrentHashMap<>();

    /**
     * Builds and flattens a call graph starting from the given method.
     *
     * @param repoId      the target repository
     * @param packageName dot-notation package (e.g. "com.java.vip.controller")
     * @param className   simple class name (e.g. "VipController")
     * @param methodName  method name or full signature (e.g. "getVip")
     * @return flattened call graph, or an empty graph if analysis fails
     */
    public FlattenedCallGraph analyzeMethod(String repoId,
                                            String packageName,
                                            String className,
                                            String methodName) {
        AnalysisResult<FlattenedCallGraph> result = analyzeMethodStructured(
                repoId, packageName, className, methodName);
        if (result.data() != null) {
            return result.data();
        }
        return FlattenedCallGraph.builder().methods(List.of()).build();
    }

    public AnalysisResult<FlattenedCallGraph> analyzeMethodStructured(String repoId,
                                                                      String packageName,
                                                                      String className,
                                                                      String methodName) {
        AnalysisMetadata metadata = AnalysisMetadata.now(repoId, packageName, className, methodName);
        ReentrantReadWriteLock.ReadLock readLock = lockFor(repoId).readLock();
        readLock.lock();
        try {
            Path repoRoot = sourceCodePort.sourceRoot(repoId);
            if (repoRoot == null) {
                return AnalysisResult.failed(
                        AnalysisErrorCode.REPO_NOT_FOUND,
                        "Repository root was not resolved",
                        repoId,
                        metadata);
            }
            String relativePath = resolveRelativePath(repoRoot, packageName, className);
            return javaCallGraphAnalyzer.analyzeFlattenedResult(repoRoot, relativePath, methodName, metadata);
        } catch (UnknownRepoException e) {
            return AnalysisResult.failed(
                    AnalysisErrorCode.REPO_NOT_FOUND,
                    "Repository was not found",
                    e.getMessage(),
                    metadata);
        } catch (Exception e) {
            log.error("Structured analysis failed for {}.{}.{}", repoId, className, methodName, e);
            return AnalysisResult.failed(
                    AnalysisErrorCode.INTERNAL_ERROR,
                    "Unexpected analysis failure",
                    e.getMessage(),
                    metadata);
        } finally {
            readLock.unlock();
        }
    }

    public ExplainableCallGraph analyzeMethodExplainable(String repoId,
                                                         String packageName,
                                                         String className,
                                                         String methodName) {
        AnalysisResult<ExplainableCallGraph> result = analyzeMethodExplainableStructured(
                repoId, packageName, className, methodName);
        if (result.data() != null) {
            return result.data();
        }
        return new ExplainableCallGraph(
                null,
                List.of(),
                List.of(),
                Map.of(),
                FlattenedCallGraph.builder().methods(List.of()).build());
    }

    public AnalysisResult<ExplainableCallGraph> analyzeMethodExplainableStructured(String repoId,
                                                                                   String packageName,
                                                                                   String className,
                                                                                   String methodName) {
        AnalysisMetadata metadata = AnalysisMetadata.now(repoId, packageName, className, methodName);
        ReentrantReadWriteLock.ReadLock readLock = lockFor(repoId).readLock();
        readLock.lock();
        try {
            Path repoRoot = sourceCodePort.sourceRoot(repoId);
            if (repoRoot == null) {
                return AnalysisResult.failed(
                        AnalysisErrorCode.REPO_NOT_FOUND,
                        "Repository root was not resolved",
                        repoId,
                        metadata);
            }
            String relativePath = resolveRelativePath(repoRoot, packageName, className);
            return javaCallGraphAnalyzer.analyzeExplainableResult(
                    repoId, repoRoot, relativePath, methodName, metadata);
        } catch (UnknownRepoException e) {
            return AnalysisResult.failed(
                    AnalysisErrorCode.REPO_NOT_FOUND,
                    "Repository was not found",
                    e.getMessage(),
                    metadata);
        } catch (Exception e) {
            log.error("Explainable analysis failed for {}.{}.{}", repoId, className, methodName, e);
            return AnalysisResult.failed(
                    AnalysisErrorCode.INTERNAL_ERROR,
                    "Unexpected analysis failure",
                    e.getMessage(),
                    metadata);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Scans and returns entry points for the given repository and types.
     *
     * @param repoId the target repository
     * @param types  entry point types to include (API, MQ, SCHEDULE); if empty, all types are used
     * @return list of entry point classes
     */
    public List<EntryPointClass> scanEntryPoints(String repoId, EntryPointType... types) {
        ReentrantReadWriteLock.ReadLock readLock = lockFor(repoId).readLock();
        readLock.lock();
        try {
            Path repoRoot = sourceCodePort.sourceRoot(repoId);
            List<EntryPointType> filters = (types == null || types.length == 0)
                    ? EntryPointType.ALL
                    : Arrays.asList(types);
            return entryPointCacheService.getEntryPoints(repoRoot, filters);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Looks up an API path and HTTP method in the trie index.
     *
     * @param apiPath    e.g. "/api/vip/123/level"
     * @param httpMethod e.g. "GET"
     * @return matching API references (0 or 1 element)
     */
    public List<ApiRef> lookupApi(String apiPath, String httpMethod) {
        Optional<ApiEntryPointRef> result = apiTrieService.lookup(apiPath, httpMethod);
        return result
                .map(ref -> new ApiRef(
                        ref.repoId(),
                        ref.packageName(),
                        ref.className(),
                        ref.methodName()))
                .map(List::of)
                .orElse(List.of());
    }

    /**
     * Evicts caches for the given repository and rebuilds the trie index.
     * Call this after a git pull to ensure stale data is discarded.
     *
     * @param repoId the repository to reload
     */
    public void reloadRepo(String repoId) {
        log.info("Reloading analysis caches for repo: {}", repoId);
        ReentrantReadWriteLock.WriteLock writeLock = lockFor(repoId).writeLock();
        writeLock.lock();
        try {
            reloadCaches(repoId);
        } finally {
            writeLock.unlock();
        }
        log.info("Reload complete for repo: {}", repoId);
    }

    /**
     * 在同一把 per-repo write lock 內執行 git 異動並重建快取
     * 確保併發分析不會讀到 checkout 或 pull 到一半的 working tree
     * git 操作失敗時例外向外傳播且不重建快取
     *
     * @param repoId 目標 repo
     * @param gitOperation 會改變 working tree 的 git 操作
     * @return git 操作的結果訊息
     */
    public String reloadRepoAfter(String repoId, Supplier<String> gitOperation) {
        Assert.notNull(gitOperation, "gitOperation must not be null");
        log.info("Executing git mutation and cache reload for repo: {}", repoId);
        ReentrantReadWriteLock.WriteLock writeLock = lockFor(repoId).writeLock();
        writeLock.lock();
        try {
            String result = gitOperation.get();
            reloadCaches(repoId);
            return result;
        } finally {
            writeLock.unlock();
        }
    }

    /** 清除並重建指定 repo 的所有分析快取，呼叫端必須已持有該 repo 的 write lock */
    private void reloadCaches(String repoId) {
        Path repoRoot = sourceCodePort.sourceRoot(repoId);
        sourceRootResolver.invalidate(repoRoot);
        projectParserService.invalidate(repoRoot);
        entryPointCacheService.reload(repoRoot);
        classMetadataService.reload(repoRoot);
        apiTrieService.reload(repoId);
    }

    /** Returns descriptors for all registered repositories */
    public List<RepoDescriptor> allRepos() {
        return repoRegistryPort.all();
    }

    /**
     * Resolves the relative file path by searching all source roots.
     * Falls back to the default single-module layout if not found.
     */
    private String resolveRelativePath(Path repoRoot, String packageName, String className) {
        String packagePath = packageName.replace('.', '/');
        String fileSuffix = packagePath + "/" + className + ".java";

        List<Path> sourceRoots = sourceRootResolver.resolveSourceRoots(repoRoot);
        for (Path sourceRoot : sourceRoots) {
            Path candidate = sourceRoot.resolve(fileSuffix);
            if (Files.exists(candidate)) {
                return repoRoot.relativize(candidate).toString();
            }
        }

        // Fallback: assume single-module layout
        log.warn("Could not find {}.{} in any source root, falling back to default path", packageName, className);
        return "src/main/java/" + fileSuffix;
    }

    private ReentrantReadWriteLock lockFor(String repoId) {
        return repoLocks.computeIfAbsent(repoId, key -> new ReentrantReadWriteLock(true));
    }
}
