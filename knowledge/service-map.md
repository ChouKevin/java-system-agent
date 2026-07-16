# Service Map

## Repositories

| repoId | Purpose | Primary documents |
|--------|---------|-------------------|
| `test-repo` | Demonstrates an order workflow service with checkout, payment confirmation, and shipping arrangement flows. | `knowledge/repos/test-repo/business-map.md` |

## How to Use This Example

1. Start from `read_service_map` to identify `test-repo`.
2. Use `read_business_map` with `repoId=test-repo` to list the available business groups.
3. Use `read_business_group_doc` with the selected `groupName` to find supported entry points.
4. Use `find_call_graph` with the entry point details listed in the business group document when source-level evidence is needed.
