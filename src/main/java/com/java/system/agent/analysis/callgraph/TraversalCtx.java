package com.java.system.agent.analysis.callgraph;

import java.nio.file.Path;
import java.util.Set;

/**
 * Immutable traversal context threaded through call graph recursion.
 * Replaces the loose parameters that were passed to every recursive method.
 *
 * <p>{@code visited} 是刻意共享的 mutable Set — 所有遞迴層級共用同一個 instance 做 cycle detection。
 * 若未來需要平行化 traversal，需改為 thread-safe 結構（如 ConcurrentHashMap.newKeySet()）
 */
public record TraversalCtx(
    Path repoRoot,
    int depth,
    int maxDepth,
    Set<String> visited
) {
    TraversalCtx deeper() {
        return new TraversalCtx(repoRoot, depth + 1, maxDepth, visited);
    }
}
