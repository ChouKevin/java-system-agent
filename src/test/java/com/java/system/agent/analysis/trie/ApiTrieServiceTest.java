package com.java.system.agent.analysis.trie;

import com.java.system.agent.analysis.entrypoint.ApiEntryPoint;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.model.EntryPointClass;
import com.java.system.agent.analysis.model.EntryPointType;
import com.java.system.agent.analysis.port.SourceCodePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ApiTrieServiceTest {

    @Mock private SourceCodePort sourceCodePort;
    @Mock private EntryPointCacheService entryPointCacheService;

    private ApiTrieService apiTrieService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        apiTrieService = new ApiTrieService(sourceCodePort, entryPointCacheService);
    }

    private void loadRepo(String repoId, List<EntryPointClass> classes) {
        Path repoRoot = Path.of("/repos/" + repoId);
        when(sourceCodePort.sourceRoot(repoId)).thenReturn(repoRoot);
        when(entryPointCacheService.getEntryPoints(repoRoot, List.of(EntryPointType.API)))
                .thenReturn(classes);
        apiTrieService.reload(repoId);
    }

    private EntryPointClass buildClass(String packagePath, String className, String basePath,
                                       String methodName, List<String> httpMethods, String fullUrl) {
        ApiEntryPoint method = ApiEntryPoint.builder()
                .name(methodName)
                .type(EntryPointType.API)
                .apiUrl(fullUrl)
                .apiType(httpMethods)
                .build();
        return EntryPointClass.builder()
                .className(className)
                .packagePath(packagePath)
                .basePath(basePath)
                .methods(List.of(method))
                .build();
    }

    @Test
    void should_find_exact_api_path() {
        EntryPointClass cls = buildClass(
                "com/java/vip/controller/VipController.java",
                "VipController", "/api/vip",
                "getVip", List.of("GET"), "/api/vip/info");
        loadRepo("test-repo", List.of(cls));

        Optional<ApiEntryPointRef> result = apiTrieService.lookup("/api/vip/info", "GET");
        assertTrue(result.isPresent());
        assertEquals("test-repo", result.get().repoId());
        assertEquals("VipController", result.get().className());
        assertEquals("com.java.vip.controller", result.get().packageName());
        assertEquals("getVip", result.get().methodName());
    }

    @Test
    void should_match_path_variable_segment() {
        EntryPointClass cls = buildClass(
                "com/java/vip/controller/VipController.java",
                "VipController", "/api/vip",
                "getById", List.of("GET"), "/api/vip/{id}/level");
        loadRepo("test-repo", List.of(cls));

        Optional<ApiEntryPointRef> result = apiTrieService.lookup("/api/vip/123/level", "GET");
        assertTrue(result.isPresent());
        assertEquals("getById", result.get().methodName());
    }

    @Test
    void should_prefer_exact_match_over_wildcard() {
        EntryPointClass exact = buildClass(
                "com/java/vip/controller/VipController.java",
                "VipController", "/api/vip",
                "getSpecial", List.of("GET"), "/api/vip/special");
        EntryPointClass wildcard = buildClass(
                "com/java/vip/controller/VipController.java",
                "VipController", "/api/vip",
                "getById", List.of("GET"), "/api/vip/{id}");
        loadRepo("test-repo", List.of(exact, wildcard));

        Optional<ApiEntryPointRef> result = apiTrieService.lookup("/api/vip/special", "GET");
        assertTrue(result.isPresent());
        assertEquals("getSpecial", result.get().methodName());
    }

    @Test
    void should_return_empty_for_unknown_path() {
        Optional<ApiEntryPointRef> result = apiTrieService.lookup("/api/nonexistent", "GET");
        assertTrue(result.isEmpty());
    }

    @Test
    void should_return_empty_for_wrong_http_method() {
        EntryPointClass cls = buildClass(
                "com/java/vip/controller/VipController.java",
                "VipController", "/api/vip",
                "getVip", List.of("GET"), "/api/vip/info");
        loadRepo("test-repo", List.of(cls));

        Optional<ApiEntryPointRef> result = apiTrieService.lookup("/api/vip/info", "POST");
        assertTrue(result.isEmpty());
    }

    @Test
    void should_handle_case_insensitive_http_method() {
        EntryPointClass cls = buildClass(
                "com/java/vip/controller/VipController.java",
                "VipController", "/api/vip",
                "getVip", List.of("GET"), "/api/vip/info");
        loadRepo("test-repo", List.of(cls));

        Optional<ApiEntryPointRef> result = apiTrieService.lookup("/api/vip/info", "get");
        assertTrue(result.isPresent());
    }

    @Test
    void should_clear_repo_entries_on_reload_with_empty() {
        EntryPointClass cls = buildClass(
                "com/java/vip/controller/VipController.java",
                "VipController", "/api/vip",
                "getVip", List.of("GET"), "/api/vip/info");
        loadRepo("test-repo", List.of(cls));
        assertTrue(apiTrieService.lookup("/api/vip/info", "GET").isPresent());

        // Reload with empty list — simulates repo having no APIs after update
        loadRepo("test-repo", List.of());
        assertTrue(apiTrieService.lookup("/api/vip/info", "GET").isEmpty());
    }

    @Test
    void should_prune_all_sub_paths_after_removal() {
        EntryPointClass cls = buildClass(
                "com/java/vip/controller/VipController.java",
                "VipController", "/api/vip",
                "getById", List.of("GET"), "/api/vip/{id}/level");
        loadRepo("test-repo", List.of(cls));
        assertTrue(apiTrieService.lookup("/api/vip/123/level", "GET").isPresent());

        // Reload with empty — all paths should be fully removed
        loadRepo("test-repo", List.of());

        // Verify no wildcard/exact sub-path leaks remain
        assertTrue(apiTrieService.lookup("/api/vip/123/level", "GET").isEmpty(),
                "Wildcard path should be fully pruned after removal");
        assertTrue(apiTrieService.lookup("/api/vip/anything", "GET").isEmpty(),
                "No phantom wildcard matches should leak");
        assertTrue(apiTrieService.lookup("/api", "GET").isEmpty(),
                "Parent segments should not become phantom endpoints");
    }

    @Test
    void should_not_affect_other_repos_on_reload() {
        EntryPointClass cls1 = buildClass(
                "com/java/vip/controller/VipController.java",
                "VipController", "/api/vip",
                "getVip", List.of("GET"), "/api/vip/info");
        EntryPointClass cls2 = buildClass(
                "com/java/bonus/controller/BonusController.java",
                "BonusController", "/api/bonus",
                "getBonus", List.of("GET"), "/api/bonus/info");

        loadRepo("repo-a", List.of(cls1));
        loadRepo("repo-b", List.of(cls2));

        // Reload repo-a with empty
        loadRepo("repo-a", List.of());

        // repo-a path gone
        assertTrue(apiTrieService.lookup("/api/vip/info", "GET").isEmpty());
        // repo-b path still present
        assertTrue(apiTrieService.lookup("/api/bonus/info", "GET").isPresent());
    }
}
