# Knowledge directory

This directory contains format examples only. It is not a store of formal local
business knowledge and must never contain `repos/{repoId}`, `service-map.md`,
generated catalogs, or indexed/query source.

Formal knowledge lives in the independent `java-system-agent-knowledge` Git
repository. That repository is the authority for curated operations and
entry-point catalogs. The current codebase is runtime truth: examples locate
candidates, while current code decides actual behavior and whether an anchor is
still present.

Every file and record under `examples/` is explicitly marked
`example_only: true`; indexers must exclude these examples from formal indexes
and query sources. They are illustrative format references, not runtime
configuration or capability contracts.

This repository does not maintain master/uat document variants or branch-specific
knowledge copies. Runtime clones and application behavior remain outside this
directory.
