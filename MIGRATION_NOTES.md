# Migration Notes

## 2026-07-05 - Demo Repository And Document Contract

### Current State

- Regression-only static analysis cases live under `src/test/resources/fixtures/*`.
- `repos/test-repo` is the committed user-facing example repository.
- The runtime document contract is `repos/service-map.md`, `repos/{repoId}/docs/business-map.md`, and `repos/{repoId}/docs/business-groups/*.md`.
- The exposed document tool for group details is `read_business_group_doc`.
- Demo repository contract coverage verifies that business group documents point to real, analyzable Java entry points.

### Remaining Directions

1. Add analyzer fixtures only for supported framework or data-access patterns that are not already covered.
2. Keep `repos/test-repo` focused on onboarding and usage examples, not regression edge cases.
3. Update `repos/service-map.md` and README onboarding instructions when adding another committed example repository.
4. Keep noisy test output low; prefer assertions over console dumps.

### Review Questions

- Which data-access libraries should become first-class fixture coverage next?
- Should future examples be split by architecture style, or should `repos/test-repo` remain the single canonical example?

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
