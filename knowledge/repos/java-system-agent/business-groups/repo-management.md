# Repo 管理

## Business Purpose

This group manages the lifecycle of every repository the agent analyzes: registering a clone, pulling updates, switching branches, and querying current state. Any git mutation must reload the per-repo analysis caches so later analysis reads the new source.

## Entry Points

| Package | Class | Method | When to inspect |
|---------|-------|--------|-----------------|
| `com.java.system.agent.api` | `RepoController` | `listRepos` | User asks which repositories are registered. |
| `com.java.system.agent.api` | `RepoController` | `cloneRepo` | User asks how a repository is first downloaded. |
| `com.java.system.agent.api` | `RepoController` | `pullRepo` | User asks how source updates reach the analyzer. |
| `com.java.system.agent.api` | `RepoController` | `checkoutRepo` | User asks how a branch or revision is switched. |
| `com.java.system.agent.api` | `RepoController` | `currentBranch` | User asks which revision is being analyzed. |

## Required Input Data

| Field | Meaning |
|-------|---------|
| `repo` | Repository identifier registered in configuration. |
| `branch` | Target branch when checking out. |
| `X-Api-Token` header | Shared secret required by all mutating git endpoints. |

## System Behavior

1. Reject mutating requests without a valid API token (fail-closed).
2. Clone or update the repository working copy under the runtime `repos/` directory.
3. After any mutation, invalidate and rebuild the per-repo analysis caches.
4. On startup, warm caches for every registered repository.

## Related Dependencies

| Dependency | Role |
|------------|------|
| `GitRepoService` | Executes clone, pull, and checkout through JGit. |
| `CacheWarmerService` | Preloads analysis caches at startup. |
| `AnalysisService` | Owns the per-repo caches that reloads must refresh. |

## Source Lookup

Use `find_call_graph` with:

| repoId | packageName | className | methodSignature |
|--------|-------------|-----------|-----------------|
| `java-system-agent` | `com.java.system.agent.api` | `RepoController` | `listRepos` |
| `java-system-agent` | `com.java.system.agent.api` | `RepoController` | `pullRepo` |
| `java-system-agent` | `com.java.system.agent.api` | `RepoController` | `checkoutRepo` |
