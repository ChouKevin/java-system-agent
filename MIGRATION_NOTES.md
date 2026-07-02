# Migration Notes

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
