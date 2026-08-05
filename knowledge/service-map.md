# Service Map

## Repositories

This hand-authored index describes repositories that the root Agent's production Java Semantic
Service HTTP adapter may expose through opaque `repoId` values. The root resolves catalog and
revision data through versioned HTTP contracts; this file remains documentation rather than a
runtime registry or source clone.

| repoId | Purpose | Primary documents |
|--------|---------|-------------------|
| `java-system-agent` | Validated Agent kernel, durable session inbox, PostgreSQL persistence, and the separately built Java code intelligence service | `knowledge/repos/java-system-agent/business-map.md` |

## Navigation

1. Use the `repoId` to open `knowledge/repos/{repoId}/business-map.md`.
2. Select a business group and read its existing document under `business-groups/`.
3. Treat a future integration or external ingress section as non-runnable: the root composition
   exists, but Slack ingress, workers, scheduling, and response delivery do not.
4. Use source-level evidence from the owning project when actual implementation behavior is needed.

The runtime must not infer confidence or rank repositories from this document. A future model may
select one or several issued repository candidates and describe unresolved scope in language; the
runtime only validates that selected opaque handles were issued and remain in scope.
