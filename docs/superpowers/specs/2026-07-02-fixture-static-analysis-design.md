# Fixture-Based Static Analysis Test Framework Design

## Context

`repos/test` is a managed demo repository. It represents a repository that the agent can inspect, document, and use in product-level examples. It should remain useful for demonstrations of Slack/tool-based Java code investigation.

Static-analysis regression tests need a different boundary. They should use small, purpose-built fixture repositories under `src/test/resources/fixtures`. Those fixtures should be stable, minimal, and paired with expected output files so call graph behavior can be reviewed and compared predictably.

## Goal

PR3 introduces a reusable fixture-based test framework for static analysis without slimming or rewriting `repos/test`.

The first fixture repository is:

```text
src/test/resources/fixtures/spring-basic
```

It covers the first stable regression cases:

- Controller -> Service -> Repository
- Interface -> single implementation
- MyBatis XML SQL mapping

## Non-Goals

PR3 does not:

- Remove or shrink `repos/test`
- Rewrite existing call graph tests around `repos/test`
- Change production call graph resolution behavior
- Introduce new database, graph store, MCP, or external service dependencies
- Add broad coverage for every planned static-analysis case

## Approach

Use a separate regression fixture repo and keep `repos/test` as a demo repo.

This keeps responsibilities separate:

- `repos/test`: demo managed repo for product flows and examples
- `src/test/resources/fixtures/*`: regression fixtures for deterministic static-analysis tests

The helper layer should support both paths over time, but PR3 only wires the new `spring-basic` fixture into fixture tests.

## Components

### Fixture Repo

`src/test/resources/fixtures/spring-basic` contains a small Java source tree:

```text
src/main/java/com/example/basic
  BasicController.java
  BasicService.java
  BasicServiceImpl.java
  BasicRepository.java

src/main/resources/mapper
  BasicRepository.xml

expected
  controller-service-repository.json
  interface-single-impl.json
  mybatis-xml.json
```

The Java classes should be as small as possible while still exercising the intended behavior.

### FixtureRepoLoader

Loads fixture repository paths from `src/test/resources/fixtures/{fixtureName}`.

Responsibilities:

- Resolve fixture root path
- Fail clearly when a fixture does not exist
- Provide helper methods for source-relative file paths

### ExpectedGraphLoader

Loads expected JSON files from the fixture's `expected` directory.

Responsibilities:

- Parse expected graph specs with Jackson
- Keep expected files separate from assertion logic
- Fail clearly when an expected file is missing or invalid

### GraphAssert

Provides focused assertions over flattened call graph output.

Responsibilities:

- Assert expected method signatures exist
- Assert expected edge-like relationships where available from flattened output
- Assert call type and SQL/code snippets for selected nodes
- Avoid snapshot-equality assertions that make tests too brittle

### CallGraphFixtureTest

Uses the fixture helpers and existing `JavaCallGraphAnalyzer` setup to validate the first fixture cases.

The test should verify behavior, not implementation details. It should also be reusable as a pattern for later fixture test classes.

## Expected Graph Format

Expected JSON should be compact and assertion-oriented, not a full copy of `FlattenedCallGraph`.

Recommended shape:

```json
{
  "rootSignature": "com.example.basic.BasicController#public String getOrder(String id)",
  "methods": [
    {
      "className": "BasicController",
      "methodName": "getOrder",
      "callType": "INTERNAL_CONTROLLER"
    }
  ],
  "containsCode": [
    {
      "className": "BasicRepository",
      "methodName": "findById",
      "snippet": "SELECT"
    }
  ]
}
```

This format gives useful regression coverage without making tests fail on harmless serialization or ordering changes.

## `repos/test` Cleanup Direction

PR3 should document, but not execute, future `repos/test` slimming.

Potential future moves:

- Move simple interface single-implementation regression from `repos/test` into fixtures.
- Move fallback interface regression into fixtures if it is not needed for demo flow.
- Keep `PaymentService` / `PaymentStrategy` in `repos/test` because it demonstrates multi-implementation, profile metadata, and business-flow explanation.
- Keep MyBatis annotation and XML examples in `repos/test` if they remain useful for product demos.
- Evaluate `Depth*`, collision, and generic limitation cases separately because they are more regression-focused than demo-focused.

## Testing

PR3 should run:

```text
mvn test
```

Expected result:

- Existing tests remain green.
- New fixture tests pass.
- No production behavior changes are required.

## Risks

- Golden files can become brittle if they mirror the full output. Use compact expected specs instead.
- Fixture helpers can become another large framework. Keep them small and focused on current needs.
- `repos/test` and fixtures can drift in purpose. Document the distinction clearly in the fixture test package and migration notes.

## Approval Status

Approved direction:

- Add fixture framework first.
- Keep `repos/test` as a demo repo.
- Defer actual `repos/test` slimming to a later PR.
