# Migration Notes

## 2026-07-04 - Follow-up Roadmap For Fixture Migration And LLM Flow

### Current State

- `uat` has fixture migration work through PR8.
- Regression-only static analysis cases are moving into deterministic fixtures under `src/test/resources/fixtures/*`.
- `generic-limitation` and `legacy-callgraph` fixtures now cover the former generic, depth, visited-signature, interface, source-extraction, and MQ detection regression cases.
- The old regression-oriented demo folder has been removed from this branch.
- `repos/test-repo` is now the committed user-facing example repository. It contains source code, mapper resources, `repos/test-repo/docs/business-map.md`, `repos/test-repo/docs/business-groups/*.md`, and `repos/test-repo/docs/summary.md`.
- The LLM document flow now depends on `repos/service-map.md`, `repos/{repoId}/docs/business-map.md`, and `repos/{repoId}/docs/business-groups/*.md`.
- Local planning notes under ignored paths should not be force-added unless the repository policy changes.

### Remaining Directions

1. Keep analyzer regression coverage fixture-based.
   - Add fixtures for multi-module Spring Boot repositories.
   - Add representative data-access variants beyond the current mapper/JPA coverage where useful, such as JDBC template, MyBatis-Plus, QueryDSL, or Spring Data JDBC.
   - Keep each fixture narrowly scoped so regression scenarios do not accumulate in the demo repository again.

2. Keep the runtime example aligned with the LLM tool chain.
   - Runtime tools resolve `repoId` to `repos/{repoId}`; `test-repo` maps to `repos/test-repo`.
   - Adapter and tool tests should continue guarding `service-map.md`, `business-map.md`, and `business-groups/{groupName}.md` paths.
   - Update `repos/service-map.md` when adding another committed example repo.

3. Improve the LLM context contract.
   - Document the outer LLM contract: `repoId`, business group, entry point, available documents, and when to call source-analysis tools.
   - Document the inner loop-engineering contract: call graph nodes, edges, evidence, confidence, and source coordinates as snapshot evidence.
   - Treat source coordinates as rebuildable analysis output because they can move when the codebase changes.

4. Reduce noisy test output.
   - Remove or gate large `System.out.println` JSON dumps in call graph tests after the fixture migration is stable.
   - Prefer assertions and focused snapshot-style checks over console output as regression evidence.

### Recommended Execution Order

1. Re-scan references to the old demo folder and confirm they only appear as deleted historical paths in git diff.
2. Add the next analyzer fixture only when it protects a real supported framework or data-access pattern.
3. Keep `repos/test-repo` focused on explaining repository usage, not regression edge cases.
4. Re-run the full Maven test suite after each fixture or demo-contract change.

### Review Questions

- Which data-access libraries should be first-class fixture coverage for the next analyzer iteration?
- Should `repos/test-repo` stay as the only committed example, or should future examples be split by architecture style?

## 2026-07-03 - Fixture-Based Static Analysis Tests

- Added `src/test/resources/fixtures/spring-basic` as the first deterministic static-analysis regression fixture.
- Kept the former managed demo repository; it is not slimmed in this change.
- Future cleanup can move duplicate regression-only cases from the former demo repository into `src/test/resources/fixtures/*` after equivalent fixture coverage exists.

## 2026-07-02 - Development Workflow Notes

- Local planning notes are intentionally ignored; do not force-add them unless the ignore policy changes.
- The fixture static-analysis design notes remain local for planning context and should not be part of repository history in the current workflow.

## 2026-07-02 - Structured Analysis Result

### Added

- Added `AnalysisResult<T>` for structured `SUCCESS`, `PARTIAL`, and `FAILED` analysis outcomes.
- Added structured analysis errors, warnings, metadata, and error codes.
- Added `AnalysisService.analyzeMethodStructured(...)` for callers that need failure details.

### Compatibility

- Existing `AnalysisService.analyzeMethod(...)` remains available and still returns an empty graph on failure for legacy controller compatibility.
- LLM tool adapters now use structured analysis results internally so failed analysis is not silently presented as a successful empty graph.

### Verification

- `mvn test` passed with 144 tests.

## 2026-07-02 - Dependency Upgrade Baseline

### Updated

- Spring Boot parent: `3.5.12` -> `3.5.16`
- Spring AI BOM: `1.1.2` -> `1.1.8`
- Spring Modulith BOM: `1.3.4` -> `1.4.12`
- springdoc OpenAPI UI: `2.8.9` -> `2.8.17`
- Slack Bolt SDK: `1.45.3` -> `1.49.0`
- Tyrus standalone client: `1.20` -> `1.22`
- JGit: `6.8.0.202311291450-r` -> `6.10.1.202505221210-r`
- JavaParser: `3.26.1` -> `3.28.2`

### Skipped

- Spring AI `2.0.0`: skipped because it is a major upgrade and should be handled in a dedicated compatibility PR.
- Spring Modulith `2.x`: skipped because it is a major upgrade and should be handled separately.
- springdoc OpenAPI `3.x`: skipped because it is a major upgrade.
- JGit `7.x`: skipped because it is a major upgrade.
- Tyrus `2.x`: skipped because it moves away from the current `javax.websocket` dependency line.

### Verification

- Baseline before upgrade: `mvn test` passed with 139 tests.
- After upgrade: `mvn test` passed with 139 tests.
