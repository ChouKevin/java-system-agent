package com.java.system.agent.analysis.entrypoint;

import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;

import com.java.system.agent.analysis.model.EntryPointClass;
import com.java.system.agent.analysis.model.EntryPointType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 測試 EntryPointCacheService
 */
class EntryPointCacheServiceTest {

    @Mock
    private ProjectParserService mockParserService;

    @Mock
    private SourceRootResolver mockSourceRootResolver;

    private EntryPointCacheService cacheService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cacheService = new EntryPointCacheService(mockParserService, mockSourceRootResolver);
    }

    @Test
    void testGetEntryPoints_EmptyRepoReturnsEmptyList(@TempDir Path tempDir) throws Exception {
        Path repoRoot = tempDir.resolve("empty-repo");
        Files.createDirectories(repoRoot);

        List<EntryPointClass> result = cacheService.getEntryPoints(repoRoot, EntryPointType.ALL);

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Empty repo should return empty list");
    }

    @Test
    void testGetEntryPoints_CacheHitDoesNotRescan(@TempDir Path tempDir) throws Exception {
        Path repoRoot = tempDir.resolve("test-repo");
        Files.createDirectories(repoRoot.resolve("src/main/java"));

        cacheService.getEntryPoints(repoRoot, EntryPointType.ALL);
        cacheService.getEntryPoints(repoRoot, EntryPointType.ALL);

        // per-repo cache：第二次不觸發 scan，parser 只被取一次
        verify(mockParserService, times(1)).getOrCreateParser(repoRoot);
    }

    @Test
    void testGetEntryPoints_DifferentFiltersShareCache(@TempDir Path tempDir) throws Exception {
        Path repoRoot = tempDir.resolve("test-repo");
        Files.createDirectories(repoRoot.resolve("src/main/java"));

        cacheService.getEntryPoints(repoRoot, EntryPointType.ALL);
        cacheService.getEntryPoints(repoRoot, List.of(EntryPointType.API));

        // 同 repo 不同 filter 共用 cache，parser 只被取一次
        verify(mockParserService, times(1)).getOrCreateParser(repoRoot);
    }

    @Test
    void testReload_RescansRepo(@TempDir Path tempDir) throws Exception {
        Path repoRoot = tempDir.resolve("test-repo");
        Files.createDirectories(repoRoot.resolve("src/main/java"));

        cacheService.getEntryPoints(repoRoot, EntryPointType.ALL);
        cacheService.reload(repoRoot);

        // reload 觸發重新掃描，parser 被取兩次
        verify(mockParserService, times(2)).getOrCreateParser(repoRoot);

        // parser invalidation 由 AnalysisService 統一管理，此處不應呼叫
        verify(mockParserService, never()).invalidate(any());
    }

    @Test
    void testInvalidateAll_ClearsCache(@TempDir Path tempDir) throws Exception {
        Path repo1 = tempDir.resolve("repo1");
        Path repo2 = tempDir.resolve("repo2");
        Files.createDirectories(repo1.resolve("src/main/java"));
        Files.createDirectories(repo2.resolve("src/main/java"));

        cacheService.getEntryPoints(repo1, EntryPointType.ALL);
        cacheService.getEntryPoints(repo2, EntryPointType.ALL);

        cacheService.invalidateAll();

        // 清除後再取，應該重新掃描
        cacheService.getEntryPoints(repo1, EntryPointType.ALL);

        verify(mockParserService, times(2)).getOrCreateParser(repo1);
        verify(mockParserService, never()).invalidateAll();
    }

    @Test
    void testFilterByTypes_ReturnsOnlyMatchingType() {
        Path repoRoot = Paths.get("repos/test");
        EntryPointCacheService realService = new EntryPointCacheService(new ProjectParserService(new SourceRootResolver()), new SourceRootResolver());

        // test repo 只有 @RabbitListener（MQ），沒有 API 和 SCHEDULE
        List<EntryPointClass> mqOnly = realService.getEntryPoints(repoRoot, List.of(EntryPointType.MQ));
        assertFalse(mqOnly.isEmpty(), "Should find MQ entry points in test repo");
        mqOnly.forEach(ep ->
                ep.methods().forEach(m ->
                        assertEquals(EntryPointType.MQ, m.type(), "All methods should be MQ type")));

        List<EntryPointClass> apiOnly = realService.getEntryPoints(repoRoot, List.of(EntryPointType.API));
        assertTrue(apiOnly.isEmpty(), "Test repo has no API entry points");

        List<EntryPointClass> scheduleOnly = realService.getEntryPoints(repoRoot, List.of(EntryPointType.SCHEDULE));
        assertTrue(scheduleOnly.isEmpty(), "Test repo has no SCHEDULE entry points");

        // ALL 應包含與 MQ-only 相同的結果
        List<EntryPointClass> all = realService.getEntryPoints(repoRoot, EntryPointType.ALL);
        assertEquals(mqOnly.size(), all.size());
    }

    @Test
    void testGetEntryPoints_HandlesNullFilter(@TempDir Path tempDir) {
        Path repoRoot = tempDir.resolve("test-repo");

        assertDoesNotThrow(() -> {
            List<EntryPointClass> result = cacheService.getEntryPoints(repoRoot, null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        });
    }
}
