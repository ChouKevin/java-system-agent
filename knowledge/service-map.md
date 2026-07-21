# Service Map

## Repositories

In production this index lists every registered repository; the LLM reads it first to decide which repo can answer the user's question. Each row points to that repo's own document set under `knowledge/repos/{repoId}/`.

| repoId | Purpose | Primary documents |
|--------|---------|-------------------|
| `java-system-agent` | The Slack AI agent itself: git repo management, static call-graph analysis, and LLM-driven Q&A. Serves as the committed demo repository. | `knowledge/repos/java-system-agent/business-map.md` |

## How to Use This Index

1. Start from `read_service_map` and match the question against each repo's purpose.
2. Use `read_business_map` with the chosen `repoId` to list its business groups.
3. Use `read_business_group_doc` with the selected `groupName` to find supported entry points.
4. Use `find_call_graph` with the entry point details listed in the business group document when source-level evidence is needed.

When several repositories look relevant, read each candidate's business map before committing to one.
