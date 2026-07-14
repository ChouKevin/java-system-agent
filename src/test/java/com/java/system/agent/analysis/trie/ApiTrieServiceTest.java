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

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void should_backtrack_to_wildcard_when_exact_branch_dead_ends() {
        EntryPointClass exactList = buildClass(
                "com/java/user/controller/UserController.java", "UserController", "/api/user",
                "listUsers", List.of("GET"), "/api/user/list");
        EntryPointClass wildcardDetail = buildClass(
                "com/java/generic/controller/GenericController.java", "GenericController", "/api",
                "getDetail", List.of("GET"), "/api/{type}/detail");
        loadRepo("test-repo", List.of(exactList, wildcardDetail));

        Optional<ApiEntryPointRef> result = apiTrieService.lookup("/api/user/detail", "GET");

        assertTrue(result.isPresent(), "wildcard route should match after exact branch dead-end");
        assertEquals("getDetail", result.get().methodName());
    }

    @Test
    void should_keep_repo_a_route_when_repo_b_registers_same_route_and_is_removed() {
        EntryPointClass fromA = buildClass(
                "com/java/a/AController.java", "AController", "/api",
                "fromA", List.of("GET"), "/api/shared/info");
        EntryPointClass fromB = buildClass(
                "com/java/b/BController.java", "BController", "/api",
                "fromB", List.of("GET"), "/api/shared/info");
        loadRepo("repo-a", List.of(fromA));
        loadRepo("repo-b", List.of(fromB));
        loadRepo("repo-b", List.of());

        Optional<ApiEntryPointRef> result = apiTrieService.lookup("/api/shared/info", "GET");

        assertTrue(result.isPresent(), "repo-a route should survive repo-b removal");
        assertEquals("repo-a", result.get().repoId());
        assertEquals("fromA", result.get().methodName());
    }

    @Test
    void should_return_lexicographically_smallest_repo_when_routes_collide() {
        EntryPointClass fromA = buildClass(
                "com/java/a/AController.java", "AController", "/api",
                "fromA", List.of("GET"), "/api/shared/info");
        EntryPointClass fromB = buildClass(
                "com/java/b/BController.java", "BController", "/api",
                "fromB", List.of("GET"), "/api/shared/info");
        loadRepo("repo-a", List.of(fromA));
        loadRepo("repo-b", List.of(fromB));

        Optional<ApiEntryPointRef> result = apiTrieService.lookup("/api/shared/info", "GET");

        assertTrue(result.isPresent());
        assertEquals("repo-a", result.get().repoId(), "collision must resolve deterministically");
    }

    @Test
    void should_fallback_to_all_method_when_request_mapping_declares_no_verb() {
        EntryPointClass cls = buildClass(
                "com/java/vip/controller/LegacyController.java", "LegacyController", "/api/legacy",
                "handleLegacy", List.of("ALL"), "/api/legacy/echo");
        loadRepo("test-repo", List.of(cls));

        Optional<ApiEntryPointRef> getResult = apiTrieService.lookup("/api/legacy/echo", "GET");
        Optional<ApiEntryPointRef> postResult = apiTrieService.lookup("/api/legacy/echo", "post");

        assertTrue(getResult.isPresent());
        assertEquals("handleLegacy", getResult.get().methodName());
        assertTrue(postResult.isPresent());
        assertEquals("handleLegacy", postResult.get().methodName());
    }

    @Test
    void should_prefer_exact_verb_over_all_fallback() {
        EntryPointClass getOnly = buildClass(
                "com/java/vip/controller/MixedController.java", "MixedController", "/api/mixed",
                "getSpecific", List.of("GET"), "/api/mixed/data");
        EntryPointClass allVerbs = buildClass(
                "com/java/vip/controller/MixedController.java", "MixedController", "/api/mixed",
                "handleAny", List.of("ALL"), "/api/mixed/data");
        loadRepo("test-repo", List.of(getOnly, allVerbs));

        Optional<ApiEntryPointRef> getResult = apiTrieService.lookup("/api/mixed/data", "GET");
        Optional<ApiEntryPointRef> deleteResult = apiTrieService.lookup("/api/mixed/data", "DELETE");

        assertTrue(getResult.isPresent());
        assertEquals("getSpecific", getResult.get().methodName());
        assertTrue(deleteResult.isPresent());
        assertEquals("handleAny", deleteResult.get().methodName());
    }

    @Test
    void should_return_all_repositories_when_same_route_exists_in_multiple_repositories() {
        EntryPointClass fromA = buildClass(
                "com/java/a/AController.java", "AController", "/api",
                "fromA", List.of("GET"), "/api/shared/{id}");
        EntryPointClass fromB = buildClass(
                "com/java/b/BController.java", "BController", "/api",
                "fromB", List.of("GET"), "/api/shared/{sharedId}");
        loadRepo("repo-b", List.of(fromB));
        loadRepo("repo-a", List.of(fromA));

        List<ApiEntryPointRef> result = apiTrieService.lookupCandidates(
                "/api/shared/123", "GET", "");

        assertThat(result).extracting(ApiEntryPointRef::repoId)
                .containsExactly("repo-a", "repo-b");
    }

    @Test
    void should_filter_candidates_when_repository_scope_is_provided() {
        EntryPointClass fromA = buildClass(
                "com/java/a/AController.java", "AController", "/api",
                "fromA", List.of("GET"), "/api/shared/{id}");
        EntryPointClass fromB = buildClass(
                "com/java/b/BController.java", "BController", "/api",
                "fromB", List.of("GET"), "/api/shared/{id}");
        loadRepo("repo-a", List.of(fromA));
        loadRepo("repo-b", List.of(fromB));

        List<ApiEntryPointRef> result = apiTrieService.lookupCandidates(
                "/api/shared/:id", "GET", "repo-b");

        assertThat(result).extracting(ApiEntryPointRef::repoId)
                .containsExactly("repo-b");
    }

    @Test
    void should_return_all_methods_when_http_method_is_missing() {
        EntryPointClass getRoute = buildClass(
                "com/java/order/OrderController.java", "OrderController", "/orders",
                "getOrder", List.of("GET"), "/orders/{id}");
        EntryPointClass deleteRoute = buildClass(
                "com/java/order/OrderController.java", "OrderController", "/orders",
                "deleteOrder", List.of("DELETE"), "/orders/{id}");
        loadRepo("order-service", List.of(getRoute, deleteRoute));

        List<ApiEntryPointRef> result = apiTrieService.lookupCandidates(
                "/orders/42", "", "");

        assertThat(result).extracting(ApiEntryPointRef::httpMethod)
                .containsExactly("DELETE", "GET");
    }

    @Test
    void should_match_zero_or_many_segments_when_route_uses_terminal_catch_all() {
        EntryPointClass route = buildClass(
                "com/java/file/FileController.java", "FileController", "/files",
                "readFile", List.of("GET"), "/files/{*path}");
        loadRepo("file-service", List.of(route));

        assertThat(apiTrieService.lookupCandidates("/files", "GET", ""))
                .hasSize(1);
        assertThat(apiTrieService.lookupCandidates("/files/a/b/c", "GET", ""))
                .hasSize(1);
    }
}
