# Migration Notes

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
