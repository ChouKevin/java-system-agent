package com.java.system.agent.analysis;

import com.java.system.agent.analysis.callgraph.JavaCallGraphAnalyzer;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.exception.UnknownRepoException;
import com.java.system.agent.analysis.model.AnalysisErrorCode;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisStatus;
import com.java.system.agent.analysis.model.AnalysisWarning;
import com.java.system.agent.analysis.model.ApiRef;
import com.java.system.agent.analysis.model.ApiRouteCandidate;
import com.java.system.agent.analysis.model.EntryPointClass;
import com.java.system.agent.analysis.model.EntryPointType;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.MethodId;
import com.java.system.agent.analysis.model.RepoDescriptor;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.port.RepoRegistryPort;
import com.java.system.agent.analysis.port.SourceCodePort;
import com.java.system.agent.analysis.trie.ApiEntryPointRef;
import com.java.system.agent.analysis.trie.ApiTrieService;
import com.java.system.agent.analysis.type.ClassMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnalysisServiceTest {

    @Mock private JavaCallGraphAnalyzer javaCallGraphAnalyzer;
    @Mock private EntryPointCacheService entryPointCacheService;
    @Mock private ApiTrieService apiTrieService;
    @Mock private ClassMetadataService classMetadataService;
    @Mock private ProjectParserService projectParserService;
    @Mock private SourceCodePort sourceCodePort;
    @Mock private RepoRegistryPort repoRegistryPort;
    @Mock private SourceRootResolver sourceRootResolver;

    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        analysisService = new AnalysisService(
                javaCallGraphAnalyzer, entryPointCacheService,
                apiTrieService, classMetadataService,
                projectParserService, sourceCodePort, repoRegistryPort,
                sourceRootResolver);
    }

    @Test
    void should_delegateToAnalyzerWithFallbackPath_when_fileNotFoundInSourceRoots() {
        Path repoRoot = Path.of("/repos/test-repo");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(sourceRootResolver.resolveSourceRoots(repoRoot))
                .thenReturn(List.of(repoRoot.resolve("src/main/java")));
        FlattenedCallGraph expected = FlattenedCallGraph.builder().methods(List.of()).build();
        when(javaCallGraphAnalyzer.analyzeFlattenedResult(
                eq(repoRoot),
                eq("src/main/java/com/example/controller/FooController.java"),
                eq("doStuff"),
                any()))
                .thenReturn(AnalysisResult.success(expected, null));

        FlattenedCallGraph result = analysisService.analyzeMethod(
                "test-repo", "com.example.controller", "FooController", "doStuff");

        assertSame(expected, result);
        verify(javaCallGraphAnalyzer).analyzeFlattenedResult(
                eq(repoRoot),
                eq("src/main/java/com/example/controller/FooController.java"),
                eq("doStuff"),
                any());
    }

    @Test
    void should_delegateToAnalyzerForExplainableGraph_when_fileNotFoundInSourceRoots() {
        Path repoRoot = Path.of("/repos/test-repo");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(sourceRootResolver.resolveSourceRoots(repoRoot))
                .thenReturn(List.of(repoRoot.resolve("src/main/java")));
        ExplainableCallGraph expected = emptyExplainableGraph();
        when(javaCallGraphAnalyzer.analyzeExplainableResult(
                eq("test-repo"),
                eq(repoRoot),
                eq("src/main/java/com/example/controller/FooController.java"),
                eq("doStuff"),
                any()))
                .thenReturn(AnalysisResult.success(expected, null));

        ExplainableCallGraph result = analysisService.analyzeMethodExplainable(
                "test-repo", "com.example.controller", "FooController", "doStuff");

        assertSame(expected, result);
        verify(javaCallGraphAnalyzer).analyzeExplainableResult(
                eq("test-repo"),
                eq(repoRoot),
                eq("src/main/java/com/example/controller/FooController.java"),
                eq("doStuff"),
                any());
    }

    @Test
    void analyzeMethodStructured_returnsFailed_whenRepoNotFound() {
        when(sourceCodePort.sourceRoot("missing-repo"))
                .thenThrow(new UnknownRepoException("missing-repo"));

        AnalysisResult<FlattenedCallGraph> result = analysisService.analyzeMethodStructured(
                "missing-repo", "com.example", "MissingService", "run");

        assertEquals(AnalysisStatus.FAILED, result.status());
        assertEquals(AnalysisErrorCode.REPO_NOT_FOUND, result.errors().get(0).code());
        assertNull(result.data());
    }

    @Test
    void analyzeMethodStructured_preservesFailedAnalyzerResult() {
        Path repoRoot = Path.of("/repos/test-repo");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(sourceRootResolver.resolveSourceRoots(repoRoot)).thenReturn(List.of());
        when(javaCallGraphAnalyzer.analyzeFlattenedResult(any(), anyString(), anyString(), any()))
                .thenReturn(AnalysisResult.failed(
                        AnalysisErrorCode.ENTRYPOINT_NOT_FOUND,
                        "Entrypoint method was not found",
                        "missing",
                        null));

        AnalysisResult<FlattenedCallGraph> result = analysisService.analyzeMethodStructured(
                "test-repo", "com.example", "MissingService", "missing");

        assertEquals(AnalysisStatus.FAILED, result.status());
        assertEquals(AnalysisErrorCode.ENTRYPOINT_NOT_FOUND, result.errors().get(0).code());
    }

    @Test
    void analyzeMethodExplainableStructured_preservesFailedAnalyzerResult() {
        Path repoRoot = Path.of("/repos/test-repo");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(sourceRootResolver.resolveSourceRoots(repoRoot)).thenReturn(List.of());
        when(javaCallGraphAnalyzer.analyzeExplainableResult(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(AnalysisResult.failed(
                        AnalysisErrorCode.ENTRYPOINT_NOT_FOUND,
                        "Entrypoint method was not found",
                        "missing",
                        null));

        AnalysisResult<ExplainableCallGraph> result = analysisService.analyzeMethodExplainableStructured(
                "test-repo", "com.example", "MissingService", "missing");

        assertEquals(AnalysisStatus.FAILED, result.status());
        assertEquals(AnalysisErrorCode.ENTRYPOINT_NOT_FOUND, result.errors().get(0).code());
    }

    @Test
    void analyzeMethod_returnsEmptyGraph_whenStructuredAnalysisFailsForCompatibility() {
        Path repoRoot = Path.of("/repos/test-repo");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(sourceRootResolver.resolveSourceRoots(repoRoot)).thenReturn(List.of());
        when(javaCallGraphAnalyzer.analyzeFlattenedResult(any(), anyString(), anyString(), any()))
                .thenReturn(AnalysisResult.failed(
                        AnalysisErrorCode.PARSE_FAILED,
                        "Failed to parse source file",
                        "bad java",
                        null));

        FlattenedCallGraph result = analysisService.analyzeMethod(
                "test-repo", "com.example", "BrokenService", "run");

        assertNotNull(result);
        assertTrue(result.getMethods().isEmpty());
    }

    @Test
    void analyzeMethodExplainable_returnsEmptyGraph_whenStructuredAnalysisFailsForCompatibility() {
        Path repoRoot = Path.of("/repos/test-repo");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(sourceRootResolver.resolveSourceRoots(repoRoot)).thenReturn(List.of());
        when(javaCallGraphAnalyzer.analyzeExplainableResult(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(AnalysisResult.failed(
                        AnalysisErrorCode.PARSE_FAILED,
                        "Failed to parse source file",
                        "bad java",
                        null));

        ExplainableCallGraph result = analysisService.analyzeMethodExplainable(
                "test-repo", "com.example", "BrokenService", "run");

        assertNotNull(result);
        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
        assertTrue(result.legacyFlattened().getMethods().isEmpty());
    }

    @Test
    void analyzeMethodStructured_distinguishesSuccessfulEmptyGraph() {
        Path repoRoot = Path.of("/repos/test-repo");
        FlattenedCallGraph emptyGraph = FlattenedCallGraph.builder().methods(List.of()).build();
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(sourceRootResolver.resolveSourceRoots(repoRoot)).thenReturn(List.of());
        when(javaCallGraphAnalyzer.analyzeFlattenedResult(any(), anyString(), anyString(), any()))
                .thenReturn(AnalysisResult.success(emptyGraph, null));

        AnalysisResult<FlattenedCallGraph> result = analysisService.analyzeMethodStructured(
                "test-repo", "com.example", "LeafService", "leaf");

        assertEquals(AnalysisStatus.SUCCESS, result.status());
        assertSame(emptyGraph, result.data());
        assertTrue(result.data().getMethods().isEmpty());
    }

    @Test
    void analyzeMethodStructured_preservesPartialResultWithWarnings() {
        Path repoRoot = Path.of("/repos/test-repo");
        FlattenedCallGraph partialGraph = FlattenedCallGraph.builder().methods(List.of()).build();
        AnalysisWarning warning = new AnalysisWarning(
                "UNRESOLVED_CALL",
                "Call graph contains an unresolved method",
                "MissingService#run");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(sourceRootResolver.resolveSourceRoots(repoRoot)).thenReturn(List.of());
        when(javaCallGraphAnalyzer.analyzeFlattenedResult(any(), anyString(), anyString(), any()))
                .thenReturn(AnalysisResult.partial(partialGraph, List.of(warning), List.of(), null));

        AnalysisResult<FlattenedCallGraph> result = analysisService.analyzeMethodStructured(
                "test-repo", "com.example", "PartialService", "run");

        assertEquals(AnalysisStatus.PARTIAL, result.status());
        assertSame(partialGraph, result.data());
        assertEquals("UNRESOLVED_CALL", result.warnings().get(0).code());
    }

    @Test
    void scanEntryPoints_defaultsToAllWhenNoTypes() {
        Path repoRoot = Path.of("/repos/test-repo");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(entryPointCacheService.getEntryPoints(repoRoot, EntryPointType.ALL))
                .thenReturn(List.of());

        List<EntryPointClass> result = analysisService.scanEntryPoints("test-repo");

        verify(entryPointCacheService).getEntryPoints(repoRoot, EntryPointType.ALL);
        assertTrue(result.isEmpty());
    }

    @Test
    void scanEntryPoints_passesSpecificTypes() {
        Path repoRoot = Path.of("/repos/test-repo");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(entryPointCacheService.getEntryPoints(repoRoot, List.of(EntryPointType.API)))
                .thenReturn(List.of());

        analysisService.scanEntryPoints("test-repo", EntryPointType.API);

        verify(entryPointCacheService).getEntryPoints(repoRoot, List.of(EntryPointType.API));
    }

    @Test
    void lookupApi_returnsApiRefWhenFound() {
        ApiEntryPointRef ref = new ApiEntryPointRef("vip-service", "com.vip.controller", "VipController", "getVip");
        when(apiTrieService.lookup("/api/vip/123", "GET")).thenReturn(Optional.of(ref));

        List<ApiRef> result = analysisService.lookupApi("/api/vip/123", "GET");

        assertEquals(1, result.size());
        assertEquals("vip-service", result.get(0).repoId());
        assertEquals("com.vip.controller", result.get(0).packageName());
        assertEquals("VipController", result.get(0).className());
        assertEquals("getVip", result.get(0).methodName());
    }

    @Test
    void lookupApi_returnsEmptyWhenNotFound() {
        when(apiTrieService.lookup("/no/such/path", "GET")).thenReturn(Optional.empty());

        List<ApiRef> result = analysisService.lookupApi("/no/such/path", "GET");

        assertTrue(result.isEmpty());
    }

    @Test
    void should_map_internal_refs_when_api_candidates_are_found() {
        ApiEntryPointRef ref = new ApiEntryPointRef(
                "order-service", "com.example.order", "OrderController", "getOrder",
                "GET", "/orders/{*}");
        when(apiTrieService.lookupCandidates("/orders/42", "GET", "order-service"))
                .thenReturn(List.of(ref));

        List<ApiRouteCandidate> result = analysisService.lookupApiCandidates(
                "/orders/42", "GET", "order-service");

        assertThat(result).containsExactly(new ApiRouteCandidate(
                "order-service", "GET", "/orders/{*}",
                "com.example.order", "OrderController", "getOrder"));
    }

    @Test
    void should_delegate_limit_when_api_candidate_suggestions_are_requested() {
        ApiEntryPointRef ref = new ApiEntryPointRef(
                "order-service", "com.example.order", "OrderController", "getOrder",
                "GET", "/orders/{*}");
        when(apiTrieService.suggestCandidates("/gateway/orders/42", "GET", "", 3))
                .thenReturn(List.of(ref));

        List<ApiRouteCandidate> result = analysisService.suggestApiCandidates(
                "/gateway/orders/42", "GET", "", 3);

        assertThat(result).extracting(ApiRouteCandidate::repoId)
                .containsExactly("order-service");
        verify(apiTrieService).suggestCandidates("/gateway/orders/42", "GET", "", 3);
        verifyNoInteractions(javaCallGraphAnalyzer);
    }

    @Test
    void should_evictAllCachesInOrder_when_reloadRepo() {
        Path repoRoot = Path.of("/repos/test-repo");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);

        analysisService.reloadRepo("test-repo");

        InOrder inOrder = inOrder(
                sourceRootResolver,
                projectParserService,
                entryPointCacheService,
                classMetadataService,
                apiTrieService);
        inOrder.verify(sourceRootResolver).invalidate(repoRoot);
        inOrder.verify(projectParserService).invalidate(repoRoot);
        inOrder.verify(entryPointCacheService).reload(repoRoot);
        inOrder.verify(classMetadataService).reload(repoRoot);
        inOrder.verify(apiTrieService).reload("test-repo");
    }

    @Test
    void should_run_git_operation_before_cache_eviction_when_reload_repo_after() {
        Path repoRoot = Path.of("/repos/test-repo");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        @SuppressWarnings("unchecked")
        Supplier<String> gitOperation = mock(Supplier.class);
        when(gitOperation.get()).thenReturn("pulled");

        String result = analysisService.reloadRepoAfter("test-repo", gitOperation);

        assertEquals("pulled", result);
        InOrder inOrder = inOrder(
                gitOperation,
                sourceRootResolver,
                projectParserService,
                entryPointCacheService,
                classMetadataService,
                apiTrieService);
        inOrder.verify(gitOperation).get();
        inOrder.verify(sourceRootResolver).invalidate(repoRoot);
        inOrder.verify(projectParserService).invalidate(repoRoot);
        inOrder.verify(entryPointCacheService).reload(repoRoot);
        inOrder.verify(classMetadataService).reload(repoRoot);
        inOrder.verify(apiTrieService).reload("test-repo");
    }

    @Test
    void should_propagate_git_failure_without_cache_eviction_when_reload_repo_after_fails() {
        Supplier<String> gitOperation = () -> {
            throw new IllegalStateException("git pull failed");
        };

        assertThrows(IllegalStateException.class,
                () -> analysisService.reloadRepoAfter("test-repo", gitOperation));

        verify(entryPointCacheService, never()).reload(any());
        verify(classMetadataService, never()).reload(any());
        verify(apiTrieService, never()).reload(anyString());

        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(Path.of("/repos/test-repo"));
        when(entryPointCacheService.getEntryPoints(any(), any())).thenReturn(List.of());
        assertTrue(analysisService.scanEntryPoints("test-repo").isEmpty());
    }

    @Test
    void allRepos_delegatesToPort() {
        List<RepoDescriptor> expected = List.of(
                new RepoDescriptor("test-repo", "Test", "desc", Path.of("/repos/test-repo")));
        when(repoRegistryPort.all()).thenReturn(expected);

        List<RepoDescriptor> result = analysisService.allRepos();

        assertSame(expected, result);
    }

    private ExplainableCallGraph emptyExplainableGraph() {
        MethodId root = new MethodId("test-repo", "com.example", "FooController", "doStuff", List.of());
        return new ExplainableCallGraph(
                root,
                List.of(),
                List.of(),
                Map.of(),
                FlattenedCallGraph.builder().methods(List.of()).build());
    }
}
