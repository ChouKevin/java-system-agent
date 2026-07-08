package com.java.system.agent.analysis.callgraph;

import java.nio.file.Path;
import java.util.Set;

/**
 * Immutable traversal context threaded through call graph recursion.
 * Replaces the loose parameters that were passed to every recursive method.
 *
 * <p>{@code visited} 是刻意共享的 mutable Set，但語意為「目前 DFS path 上的祖先 signature」。
 * 進入節點時加入、離開時移除（見 CallGraphBuilder.buildGraph 的 finally 回溯），
 * 因此只有真正的循環（祖先重現）會被標為 CYCLE_BACK_EDGE。
 * 若未來需要平行化 traversal，需改為 per-path 獨立結構。
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
