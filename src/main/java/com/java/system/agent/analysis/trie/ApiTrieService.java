package com.java.system.agent.analysis.trie;

import com.java.system.agent.analysis.entrypoint.ApiEntryPoint;
import com.java.system.agent.analysis.model.EntryPointClass;
import com.java.system.agent.analysis.model.EntryPointMethod;
import com.java.system.agent.analysis.model.EntryPointType;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.port.SourceCodePort;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<String, List<String>> repoPaths = new HashMap<>();

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

    /** 查詢 API 路徑，exact match 優先，其次 wildcard */
    public Optional<ApiEntryPointRef> lookup(String apiPath, String httpMethod) {
        lock.readLock().lock();
        try {
            String[] segments = splitPath(apiPath);
            ApiTrieNode node = root;

            for (String segment : segments) {
                ApiTrieNode exact = node.children.get(segment);
                if (exact != null) {
                    node = exact;
                } else {
                    ApiTrieNode wildcard = node.children.get(ApiTrieNode.WILDCARD);
                    if (wildcard != null) {
                        node = wildcard;
                    } else {
                        return Optional.empty();
                    }
                }
            }

            return Optional.ofNullable(node.methodMap.get(httpMethod.toUpperCase()));
        } finally {
            lock.readLock().unlock();
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void insertAll(String repoId, List<EntryPointClass> classes) {
        for (EntryPointClass cls : classes) {
            String packageName = toPackageName(cls.packagePath(), cls.className());
            for (EntryPointMethod method : cls.methods()) {
                if (!(method instanceof ApiEntryPoint api)) continue;
                if (api.apiUrl() == null || api.apiType() == null) continue;
                for (String httpMethod : api.apiType()) {
                    ApiEntryPointRef ref = new ApiEntryPointRef(
                            repoId, packageName, cls.className(), api.name());
                    insertPath(api.apiUrl(), httpMethod.toUpperCase(), ref);
                    repoPaths.computeIfAbsent(repoId, k -> new ArrayList<>())
                             .add(api.apiUrl() + "|" + httpMethod.toUpperCase());
                }
            }
        }
    }

    private void removeRepo(String repoId) {
        List<String> paths = repoPaths.remove(repoId);
        if (paths == null) return;
        for (String pathAndMethod : paths) {
            String[] parts = pathAndMethod.split("\\|", 2);
            removePath(parts[0], parts[1]);
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

    private void insertPath(String apiPath, String httpMethod, ApiEntryPointRef ref) {
        String[] segments = splitPath(apiPath);
        ApiTrieNode node = root;
        for (String segment : segments) {
            String key = segment.startsWith("{") ? ApiTrieNode.WILDCARD : segment;
            node = node.children.computeIfAbsent(key, k -> new ApiTrieNode());
        }
        node.methodMap.put(httpMethod, ref);
    }

    private void removePath(String apiPath, String httpMethod) {
        String[] segments = splitPath(apiPath);
        Deque<Map.Entry<String, ApiTrieNode>> stack = new ArrayDeque<>();
        ApiTrieNode node = root;

        for (String segment : segments) {
            String key = segment.startsWith("{") ? ApiTrieNode.WILDCARD : segment;
            ApiTrieNode child = node.children.get(key);
            if (child == null) return;
            stack.push(Map.entry(key, node));
            node = child;
        }

        node.methodMap.remove(httpMethod);

        // 回溯清理空節點
        while (!stack.isEmpty()) {
            if (!node.methodMap.isEmpty() || !node.children.isEmpty()) break;
            Map.Entry<String, ApiTrieNode> parent = stack.pop();
            parent.getValue().children.remove(parent.getKey());
            node = parent.getValue();
        }
    }

    private static String[] splitPath(String path) {
        return Arrays.stream(path.split("/"))
                .filter(s -> !s.isBlank())
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
