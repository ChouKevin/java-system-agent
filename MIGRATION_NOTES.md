# Migration Notes

## 2026-07-04 - Follow-up Roadmap For Fixture Migration And LLM Flow

### Current State

- `uat` has fixture migration work through PR8.
- Regression-only static analysis cases are being moved from `repos/test` into deterministic fixtures under `src/test/resources/fixtures/*`.
- `repos/test` is still retained because it may still serve demo, legacy test, and historical behavior roles.
- The user-facing LLM flow still depends on runtime repository paths and documentation under `repos/{repoId}/`, including `repos/service-map.md`, `repos/{repoId}/docs/business-map.md`, and `repos/{repoId}/docs/skills/*.md`.
- `docs/superpowers/` is intentionally ignored by `.gitignore`; do not force-add Superpowers-generated planning files unless the repository policy changes.

### Remaining Directions

1. Finish separating regression fixtures from the demo repository.
   - Move remaining regression-focused coverage, such as generic resolution cases, into dedicated fixture folders.
   - Review tests that still mock or reference `/repos/test` and decide whether those references are intentional demo behavior or only legacy naming.
   - Keep `repos/test` until `rg -n "repos/test" src/test/java src/test/resources repos` shows only intentional demo references remain.

2. Align the runtime demo repository with the LLM tool chain.
   - Runtime tools resolve `repoId` to `repos/{repoId}`; for example, `test-repo` should map to `repos/test-repo`.
   - If a maintained demo repository is needed, add or clone it as `repos/test-repo` and update `repos/service-map.md`.
   - Keep adapter tests that guard the expected documentation paths used by Slack, tool, and API flows.

3. Improve the LLM context contract.
   - Document the outer LLM contract: `repoId`, business group, entry point, available documents, and when to call source-analysis tools.
   - Document the inner loop-engineering contract: call graph nodes, edges, evidence, confidence, and source coordinates as snapshot evidence.
   - Treat source coordinates as rebuildable analysis output because they can move when the codebase changes.

4. Continue analyzer fixture coverage.
   - Add fixtures for multi-module Spring Boot repositories.
   - Add representative data-access variants beyond the current mapper/JPA coverage where useful, such as JDBC template, MyBatis-Plus, QueryDSL, or Spring Data JDBC.
   - Keep each fixture narrowly scoped so duplicate fixture cases do not recreate a second `repos/test`.

5. Reduce noisy test output.
   - Remove or gate large `System.out.println` JSON dumps in call graph tests after the fixture migration is stable.
   - Prefer assertions and focused snapshot-style checks over console output as regression evidence.

6. Finalize the `repos/test` slim-down decision.
   - Do not delete `repos/test` until the LLM question-to-tool flow has a replacement demo or an explicit decision that no demo repo is required.
   - If `repos/test` remains a demo repo, document that purpose clearly and remove only duplicated regression-only content.
   - If `repos/test` is replaced by `repos/test-repo`, migrate service-map and business-doc examples before deleting the old folder.

### Recommended Execution Order

1. Migrate remaining generic-resolution and legacy call graph cases to fixtures.
2. Re-scan references to `repos/test` and classify each as demo, runtime contract, or legacy test naming.
3. Decide whether the runtime demo should be `repos/test`, `repos/test-repo`, or an external cloned repo.
4. Update demo repository documentation and `repos/service-map.md` only after the runtime path decision is made.
5. Add or update tests that protect the LLM path contract.
6. Remove duplicated fixture/demo content and then re-run the full Maven test suite.

### Review Questions

- Should `repos/test` remain as the named demo repository, or should it be migrated to `repos/test-repo` to match existing adapter expectations?
- Should the demo repository be committed into this repo, cloned during setup, or generated as a fixture-like sample?
- Which data-access libraries should be first-class fixture coverage for the next analyzer iteration?

## 2026-07-03 - Fixture-Based Static Analysis Tests

- Added `src/test/resources/fixtures/spring-basic` as the first deterministic static-analysis regression fixture.
- Kept `repos/test` as a managed demo repository; it is not slimmed in this change.
- Future cleanup can move duplicate regression-only cases from `repos/test` into `src/test/resources/fixtures/*` after equivalent fixture coverage exists.

## 2026-07-02 - Development Workflow Notes

- `docs/superpowers/` is intentionally ignored by `.gitignore`; do not force-add Superpowers-generated specs unless the ignore policy changes.
- The fixture static-analysis design spec remains local under `docs/superpowers/specs/` for planning context and should not be part of repository history in the current workflow.

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
