package com.java.system.agent.analysis.trie;

import com.java.system.agent.analysis.entrypoint.ApiEntryPoint;
import com.java.system.agent.analysis.model.EntryPointClass;
import com.java.system.agent.analysis.model.EntryPointMethod;
import com.java.system.agent.analysis.model.EntryPointType;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.port.SourceCodePort;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Service
public class ApiTrieService {

    private final SourceCodePort sourceCodePort;
    private final EntryPointCacheService entryPointCacheService;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final ApiTrieNode root = new ApiTrieNode();
    private final Map<String, List<ApiEntryPointRef>> repoEntries = new HashMap<>();

    public ApiTrieService(SourceCodePort sourceCodePort,
                          EntryPointCacheService entryPointCacheService) {
        this.sourceCodePort = sourceCodePort;
        this.entryPointCacheService = entryPointCacheService;
    }

    /** 移除指定 repo 的 trie 項目並重建 */
    public void reload(String repoId) {
        log.info("Reloading API trie for repo: {}", repoId);
        lock.writeLock().lock();
        try {
            removeRepo(repoId);
            buildRepo(repoId);
        } finally {
            lock.writeLock().unlock();
        }
        log.info("API trie reload complete for repo: {}", repoId);
    }

    /** 查詢 API 路徑的所有候選項目，exact match 優先，其次 wildcard。 */
    public List<ApiEntryPointRef> lookupCandidates(
            String apiPath, String httpMethod, String repoScope) {
        lock.readLock().lock();
        try {
            NormalizedApiPath normalized = ApiPathNormalizer.normalize(apiPath, httpMethod);
            return search(root, splitPath(normalized.path()), 0,
                    normalized.httpMethod(), repoScope);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 查詢 API 路徑，並回傳排序後的第一個候選項目。 */
    public Optional<ApiEntryPointRef> lookup(String apiPath, String httpMethod) {
        return lookupCandidates(apiPath, httpMethod, "").stream().findFirst();
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void insertAll(String repoId, List<EntryPointClass> classes) {
        for (EntryPointClass cls : classes) {
            String packageName = toPackageName(cls.packagePath(), cls.className());
            for (EntryPointMethod method : cls.methods()) {
                if (!(method instanceof ApiEntryPoint api)) continue;
                if (Objects.isNull(api.apiUrl()) || Objects.isNull(api.apiType())) continue;
                for (String httpMethod : api.apiType()) {
                    NormalizedApiPath normalized = ApiPathNormalizer.normalize(
                            api.apiUrl(), httpMethod);
                    ApiEntryPointRef ref = new ApiEntryPointRef(
                            repoId,
                            packageName,
                            cls.className(),
                            api.name(),
                            normalized.httpMethod(),
                            normalized.path());
                    insertPath(ref);
                    repoEntries.computeIfAbsent(repoId, ignored -> new ArrayList<>()).add(ref);
                }
            }
        }
    }

    private void removeRepo(String repoId) {
        List<ApiEntryPointRef> refs = repoEntries.remove(repoId);
        if (CollectionUtils.isEmpty(refs)) {
            return;
        }
        for (ApiEntryPointRef ref : refs) {
            removePath(ref.routeTemplate(), ref.httpMethod(), ref.repoId());
        }
    }

    private void buildRepo(String repoId) {
        try {
            Path repoRoot = sourceCodePort.sourceRoot(repoId);
            List<EntryPointClass> classes = entryPointCacheService.getEntryPoints(
                    repoRoot, List.of(EntryPointType.API));
            insertAll(repoId, classes);
            log.debug("Inserted {} classes for repo: {}", classes.size(), repoId);
        } catch (Exception e) {
            log.warn("Failed to build trie for repo {}: {}", repoId, e.getMessage());
        }
    }

    private void insertPath(ApiEntryPointRef ref) {
        String[] segments = splitPath(ref.routeTemplate());
        ApiTrieNode node = root;
        for (String segment : segments) {
            node = node.children.computeIfAbsent(segment, ignored -> new ApiTrieNode());
        }
        Map<String, ApiEntryPointRef> refsByRepo = node.methodMap.computeIfAbsent(
                ref.httpMethod(), ignored -> new HashMap<>());
        refsByRepo.put(ref.repoId(), ref);
        warnOnCollision(ref.routeTemplate(), ref.httpMethod(), refsByRepo);
    }

    private void removePath(String apiPath, String httpMethod, String repoId) {
        String[] segments = splitPath(apiPath);
        Deque<Map.Entry<String, ApiTrieNode>> stack = new ArrayDeque<>();
        ApiTrieNode node = root;

        for (String segment : segments) {
            ApiTrieNode child = node.children.get(segment);
            if (Objects.isNull(child)) return;
            stack.push(Map.entry(segment, node));
            node = child;
        }

        Map<String, ApiEntryPointRef> refsByRepo = node.methodMap.get(httpMethod);
        if (!CollectionUtils.isEmpty(refsByRepo)) {
            refsByRepo.remove(repoId);
            if (CollectionUtils.isEmpty(refsByRepo)) {
                node.methodMap.remove(httpMethod);
            }
        }

        // 回溯清理空節點
        while (!CollectionUtils.isEmpty(stack)) {
            if (!CollectionUtils.isEmpty(node.methodMap)
                    || !CollectionUtils.isEmpty(node.children)) break;
            Map.Entry<String, ApiTrieNode> parent = stack.pop();
            parent.getValue().children.remove(parent.getKey());
            node = parent.getValue();
        }
    }

    private List<ApiEntryPointRef> search(
            ApiTrieNode node,
            String[] segments,
            int index,
            String httpMethod,
            String repoScope) {
        if (index == segments.length) {
            List<ApiEntryPointRef> direct = resolveRefs(node, httpMethod, repoScope);
            if (!CollectionUtils.isEmpty(direct)) {
                return direct;
            }
            ApiTrieNode rest = node.children.get(ApiTrieNode.REST_WILDCARD);
            return Objects.nonNull(rest) ? resolveRefs(rest, httpMethod, repoScope) : List.of();
        }

        ApiTrieNode exact = node.children.get(segments[index]);
        if (Objects.nonNull(exact)) {
            List<ApiEntryPointRef> exactResult = search(
                    exact, segments, index + 1, httpMethod, repoScope);
            if (!CollectionUtils.isEmpty(exactResult)) {
                return exactResult;
            }
        }

        ApiTrieNode wildcard = node.children.get(ApiTrieNode.WILDCARD);
        if (Objects.nonNull(wildcard) && wildcard != exact) {
            List<ApiEntryPointRef> wildcardResult = search(
                    wildcard, segments, index + 1, httpMethod, repoScope);
            if (!CollectionUtils.isEmpty(wildcardResult)) {
                return wildcardResult;
            }
        }

        ApiTrieNode rest = node.children.get(ApiTrieNode.REST_WILDCARD);
        return Objects.nonNull(rest) ? resolveRefs(rest, httpMethod, repoScope) : List.of();
    }

    private List<ApiEntryPointRef> resolveRefs(
            ApiTrieNode node, String httpMethod, String repoScope) {
        if (StringUtils.hasText(httpMethod)) {
            List<ApiEntryPointRef> exact = sortedRefs(
                    node.methodMap.get(httpMethod), repoScope);
            if (!CollectionUtils.isEmpty(exact)) {
                return exact;
            }
            return sortedRefs(node.methodMap.get(ApiTrieNode.METHOD_ALL), repoScope);
        }
        return node.methodMap.values().stream()
                .flatMap(refs -> refs.values().stream())
                .filter(ref -> !StringUtils.hasText(repoScope) || repoScope.equals(ref.repoId()))
                .sorted(candidateComparator())
                .toList();
    }

    private List<ApiEntryPointRef> sortedRefs(
            Map<String, ApiEntryPointRef> refsByRepo, String repoScope) {
        if (CollectionUtils.isEmpty(refsByRepo)) {
            return List.of();
        }
        return refsByRepo.values().stream()
                .filter(ref -> !StringUtils.hasText(repoScope) || repoScope.equals(ref.repoId()))
                .sorted(candidateComparator())
                .toList();
    }

    private Comparator<ApiEntryPointRef> candidateComparator() {
        return Comparator.comparing(ApiEntryPointRef::repoId)
                .thenComparing(ApiEntryPointRef::httpMethod)
                .thenComparing(ApiEntryPointRef::routeTemplate);
    }

    private void warnOnCollision(String apiPath,
                                 String httpMethod,
                                 Map<String, ApiEntryPointRef> refsByRepo) {
        if (refsByRepo.size() > 1) {
            log.warn("API route collision for {} {} across repos: {}",
                    httpMethod, apiPath, refsByRepo.keySet());
        }
    }

    private static String[] splitPath(String path) {
        return Arrays.stream(path.split("/"))
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
    }

    /**
     * Converts packagePath (e.g. "com/java/vip/controller/VipController.java")
     * to dot-notation package name (e.g. "com.java.vip.controller").
     */
    private static String toPackageName(String packagePath, String className) {
        return packagePath
                .replace("/" + className + ".java", "")
                .replace("/", ".");
    }
}
