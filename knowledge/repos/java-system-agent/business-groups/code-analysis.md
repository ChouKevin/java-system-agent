# 程式碼分析

## Business Purpose

This group produces call graphs from Java source. A caller supplies either method coordinates (package, class, method) or an API path; the analyzer traverses calls up to a configured depth and returns a graph with per-call confidence and evidence, ready for business translation.

## Entry Points

| Package | Class | Method | When to inspect |
|---------|-------|--------|-----------------|
| `com.java.system.agent.api` | `CallGraphController` | `getCallGraph` | User asks how a specific method's call chain is produced. |
| `com.java.system.agent.api` | `CallGraphController` | `getCallGraphFlatten` | User asks about the flattened graph format. |
| `com.java.system.agent.api` | `AnalysisController` | `getApiCallGraph` | User asks how an API path is resolved to an entry point. |

## Required Input Data

| Field | Meaning |
|-------|---------|
| `repo` | Repository identifier to analyze. |
| `packageName` / `className` / `methodSignature` | Method coordinates for direct analysis. |
| `apiPath` / `httpMethod` | Route used for API-path lookup; only a unique candidate proceeds. |

## System Behavior

1. Resolve the entry point, by coordinates or through the API route trie.
2. Traverse calls depth-first up to the configured depth; deeper nodes are marked `TRAVERSAL_CUTOFF`.
3. Attach confidence, resolution strategy, and evidence to every edge.
4. Return the graph; ambiguous or missing routes return `AMBIGUOUS` / `NOT_FOUND` instead of a graph.

## Related Dependencies

| Dependency | Role |
|------------|------|
| `AnalysisService` | Facade guarding per-repo caches with read/write locks. |
| `CallGraphBuilder` | Performs the depth-first traversal. |
| `ApiTrieService` | Matches API paths to controller methods. |
| `MapperXmlSqlExtractor` | Supplies MyBatis SQL for data-access nodes. |

## Source Lookup

Use `find_call_graph` with:

| repoId | packageName | className | methodSignature |
|--------|-------------|-----------|-----------------|
| `java-system-agent` | `com.java.system.agent.api` | `CallGraphController` | `getCallGraph` |
| `java-system-agent` | `com.java.system.agent.api` | `AnalysisController` | `getApiCallGraph` |
