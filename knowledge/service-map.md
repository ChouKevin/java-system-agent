# Service Map

## Repositories

This hand-authored index describes repositories that a future Agent catalog adapter may expose
through opaque `repoId` values. The current root Agent has no production catalog or document-reading
adapter, so this file is documentation rather than an active runtime registry.

| repoId | Purpose | Primary documents |
|--------|---------|-------------------|
| `java-system-agent` | Validated Agent kernel, durable session inbox, PostgreSQL persistence, and the separately built Java semantic service | `knowledge/repos/java-system-agent/business-map.md` |

## Navigation

1. Use the `repoId` to open `knowledge/repos/{repoId}/business-map.md`.
2. Select a business group and read its existing document under `business-groups/`.
3. Treat any section marked as a contract or future integration as non-runnable until a production
   adapter and composition root exist.
4. Use source-level evidence from the owning project when actual implementation behavior is needed.

The runtime must not infer confidence or rank repositories from this document. A future model may
select one or several issued repository candidates and describe unresolved scope in language; the
runtime only validates that selected opaque handles were issued and remain in scope.
