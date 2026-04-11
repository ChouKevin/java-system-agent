package com.java.system.agent.analysis;

import com.java.system.agent.analysis.callgraph.JavaCallGraphAnalyzer;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.model.ApiRef;
import com.java.system.agent.analysis.model.EntryPointClass;
import com.java.system.agent.analysis.model.EntryPointType;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
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
        Path repoRoot = Path.of("/repos/test");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(sourceRootResolver.resolveSourceRoots(repoRoot))
                .thenReturn(List.of(repoRoot.resolve("src/main/java")));
        FlattenedCallGraph expected = FlattenedCallGraph.builder().methods(List.of()).build();
        when(javaCallGraphAnalyzer.analyzeFlattened(
                repoRoot,
                "src/main/java/com/example/controller/FooController.java",
                "doStuff"))
                .thenReturn(expected);

        FlattenedCallGraph result = analysisService.analyzeMethod(
                "test-repo", "com.example.controller", "FooController", "doStuff");

        assertSame(expected, result);
        verify(javaCallGraphAnalyzer).analyzeFlattened(
                repoRoot,
                "src/main/java/com/example/controller/FooController.java",
                "doStuff");
    }

    @Test
    void scanEntryPoints_defaultsToAllWhenNoTypes() {
        Path repoRoot = Path.of("/repos/test");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);
        when(entryPointCacheService.getEntryPoints(repoRoot, EntryPointType.ALL))
                .thenReturn(List.of());

        List<EntryPointClass> result = analysisService.scanEntryPoints("test-repo");

        verify(entryPointCacheService).getEntryPoints(repoRoot, EntryPointType.ALL);
        assertTrue(result.isEmpty());
    }

    @Test
    void scanEntryPoints_passesSpecificTypes() {
        Path repoRoot = Path.of("/repos/test");
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
    void should_evictAllCachesInOrder_when_reloadRepo() {
        Path repoRoot = Path.of("/repos/test");
        when(sourceCodePort.sourceRoot("test-repo")).thenReturn(repoRoot);

        analysisService.reloadRepo("test-repo");

        var inOrder = inOrder(sourceRootResolver, projectParserService, entryPointCacheService, classMetadataService, apiTrieService);
        inOrder.verify(sourceRootResolver).invalidate(repoRoot);
        inOrder.verify(projectParserService).invalidate(repoRoot);
        inOrder.verify(entryPointCacheService).reload(repoRoot);
        inOrder.verify(classMetadataService).reload(repoRoot);
        inOrder.verify(apiTrieService).reload("test-repo");
    }

    @Test
    void allRepos_delegatesToPort() {
        List<RepoDescriptor> expected = List.of(
                new RepoDescriptor("test-repo", "Test", "desc", Path.of("/repos/test")));
        when(repoRegistryPort.all()).thenReturn(expected);

        List<RepoDescriptor> result = analysisService.allRepos();

        assertSame(expected, result);
    }
}
