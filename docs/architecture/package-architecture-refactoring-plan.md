# Package Architecture Refactoring Plan

> Baseline: `uat` at `5abde08234d96309c23ca69e910ec5372b3607e4`
>
> This document is the implementation contract for the draft refactoring PR. Production changes should be added to the same branch in phase-scoped commits.

## 1. Decision

Keep the current top-level application boundaries.

The root Agent remains an eight-module Spring Modulith application:

- `answering`
- `interaction`
- `persistence`
- `capability`
- `codeintelligence`
- `model`
- `slack`
- `worker`

`java-semantic-service` remains a separate Maven application and must not become a root Maven module or share an implementation library with the Agent.

The refactoring should improve package ownership and reduce oversized coordinators without changing the externally observable behavior of either application.

## 2. Merge-blocking scope

The following work is required before this PR is ready for review:

1. Remove the `interaction.port.out` dependency on the `answering.port.in` result DTO.
2. Split `ValidatedAgentLoop` into a thin lifecycle coordinator and cohesive internal collaborators.
3. Split `Lsp4jJavaSemanticService` into a facade plus protocol-specific clients and mapping helpers.
4. Extract safe, stateless responsibilities from `DefaultJdtWorkspaceManager` while retaining one owner for mutable workspace lifecycle state.
5. Add or strengthen architecture tests for the new boundaries.
6. Keep both ordinary Maven test suites green.

The graph-builder consolidation and `trie` package rename are recorded as follow-up work and do not block this PR unless implementation remains small and clearly reviewable.

## 3. Non-goals

Do not introduce any of the following as part of this refactor:

- New Agent actions or capabilities
- Changes to QUERY, ANSWER, or CLARIFY semantics
- Database schema or Flyway migrations
- HTTP or OpenAPI contract changes
- New distributed ownership, leases, or cross-machine coordination
- New Maven aggregation or shared implementation modules
- Changes to retry ceilings, budget semantics, cancellation arbitration, or revision pinning
- A generic framework that hides the distinct incoming and outgoing call-graph algorithms
- Multiple Spring components that independently mutate the same JDT workspace registry

## 4. Invariants that must remain true

### 4.1 Agent lifecycle

- `AgentTransitionCommitter` remains the only application-level path that applies an `AgentEvent` and persists its candidate `AgentRunState`.
- Event append and current-state replacement remain one atomic persistence operation.
- Terminal acceptance continues to arbitrate against durable cancellation.
- Session history remains append-only and separate from the Agent event trace.
- A pending answer-verification checkpoint resumes by invoking only the verifier.
- Terminal reconciliation must not invoke the action model, capability executors, or answer verifier.
- Repository revision drift must continue to invalidate stale issued handles before further execution.
- The model remains responsible for semantic action selection; reducers and handlers must not rank or rewrite model-selected candidates.

### 4.2 JDT LS lifecycle

- A workspace session remains bound to the repository revision used at startup.
- No new request may enter a closing, stopped, invalidated, or unusable session.
- The document lock continues to cover the full `didOpen` → query → `didClose` lifecycle for a URI.
- Workspace startup, invalidation, shutdown, capacity eviction, and maintenance termination preserve their current lock and process-ownership behavior.
- A failed or interrupted process termination must not be reported as confirmed.
- LSP4J and Eclipse JDT types remain confined to `com.java.semantic.semantic.adapter.jdtls..` and `com.java.semantic.syntax.adapter.jdt..` as currently allowed by ArchUnit.

## 5. Phase 0 — establish the baseline

Before moving production code, run and record the current result of:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml clean test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml test -Dtest=ApplicationModularityTests
mvn -f java-semantic-service/pom.xml clean test
mvn -f java-semantic-service/pom.xml test -Dtest=ArchitectureTest
```

When `JDTLS_HOME` is available and a lifecycle or semantic-resolution path changes, also run:

```bash
JDTLS_HOME=/opt/jdtls mvn -f java-semantic-service/pom.xml -Pjdtls-it test
```

Do not rewrite tests merely to make a refactor pass. Preserve behavioral assertions and add characterization coverage before moving logic whose behavior is not already explicit.

## 6. Phase 1 — remove the cross-module application DTO leak

### Problem

`interaction.port.out.SessionInboxPort` currently accepts `answering.port.in.AnswerQuestionResult`. This makes an outbound persistence contract depend on another module's inbound use-case DTO and forces `persistence` to allow `answering :: port-in`.

### Required design

Add a transport-neutral final response value owned by `interaction`, for example:

```java
package com.java.system.agent.interaction.domain.delivery;

public record FinalInteractionResponse(
        AnalysisRunId runId,
        RunOutcome outcome,
        RunResponseKind responseKind,
        String responseText) {
}
```

The exact name may change, but the type must contain only the fields required to complete the inbox row and create the final delivery entry. It must not expose `AnswerDocument`, verifier details, or the complete `AnswerQuestionResult` contract.

### Required changes

- [ ] Add the interaction-owned final response value and constructor invariants.
- [ ] Change `SessionInboxPort.completeWithFinal(...)` to accept the interaction-owned value.
- [ ] Map `AnswerQuestionResult` to that value inside `SessionInboxProcessor`.
- [ ] Update `PostgresSessionInboxAdapter` and its tests.
- [ ] Remove `answering :: port-in` from `persistence/package-info.java`.
- [ ] Update `ApplicationModularityTests` to assert the narrower persistence dependency list.
- [ ] Add an ArchUnit rule preventing `interaction.port.out..` from depending on `answering.port.in..`.

### Acceptance criteria

- No production class in `interaction.port.out..` imports a type from `answering.port.in..`.
- The persistence module depends only on the required answering domain/outbound contracts and interaction contracts.
- Completion, failure, retry, capacity deferral, and terminal delivery tests retain their current behavior.

## 7. Phase 2 — split `ValidatedAgentLoop`

### Problem

`ValidatedAgentLoop` currently owns persisted-run dispatch, bootstrap, retry, capacity resume, terminal reconciliation, the action loop, QUERY execution, answer verification, clarification acceptance, revision validation, terminal persistence, integration-failure handling, and lifecycle telemetry.

It also receives a large set of ports, validators, and state helpers through one constructor. The class is therefore the primary change-collision point in the Agent.

### Target package structure

Keep every extracted class internal to the `answering` module:

```text
answering/application/
  ValidatedAgentLoop.java
  ContextIssuer.java

answering/application/execution/
  AgentRunRecoveryCoordinator.java
  QueryActionExecutor.java
  AnswerActionExecutor.java
  TerminalResponseCoordinator.java
  AgentLoopTelemetry.java
```

Names may be adjusted to match the repository suffix vocabulary, but each class must have one clearly stated lifecycle responsibility.

### Responsibility split

#### `ValidatedAgentLoop`

Retain only:

- Request validation and top-level execution-mode dispatch
- The bounded action loop
- Action validation
- Dispatch to QUERY, ANSWER, or CLARIFY collaborators
- Updating the local current state/result returned by collaborators

It should not contain JDBC concerns, raw capability-result validation details, answer-verifier protocol mapping, or logging-category construction.

#### `AgentRunRecoveryCoordinator`

Own:

- Persisted-state lookup and request-identity validation
- Initial bootstrap claim conflict handling
- Retry attempt restart
- Capacity resume preparation
- Pending verification resume dispatch
- Pending terminal response dispatch
- Terminal reconciliation

It must not call the action model while performing terminal reconciliation.

#### `QueryActionExecutor`

Own:

- Selected candidate and repository revision resolution
- Drift detection and controlled attempt restart
- Capability invocation construction
- Capability execution result contract validation
- Unscoped result revision validation
- Context reissue and result issuance
- Recording capability failure or conflict observations

It must continue to route every state change through the shared transition boundary.

#### `AnswerActionExecutor`

Own:

- Answer-document validation
- Creating and resuming the pending verification checkpoint
- Invoking the verifier
- Verifier result compatibility checks
- Accepted/rejected answer handling
- Rejection observations
- Verifier unavailability and contract-failure mapping

#### `TerminalResponseCoordinator`

Own:

- Clarification acceptance
- Session append for accepted terminal turns
- Concluding accepted terminal responses
- Runtime notice conclusion
- Conversion from final state to `AgentLoopResult`

#### `AgentLoopTelemetry`

Own:

- Capability catalog timing
- Repository catalog timing
- Repository revision timing
- Capability execution timing
- Answer verification timing
- Stable result-category mapping

Telemetry extraction must not swallow or transform domain exceptions.

### Transition ownership rule

Do not give each collaborator an independent persistence strategy.

Use the existing `AgentTransitionCommitter`, or introduce one internal wrapper such as `AgentRunTransitions`, as the single mutation gateway shared by all collaborators. The gateway must preserve the existing conversion of commit failures into `AgentLoopException` and the separate terminal-acceptance path.

### Required changes

- [ ] Add characterization tests for execution-mode routing before moving recovery logic.
- [ ] Add focused tests for revision drift, capability contract failure, pending verification resume, cancellation, and terminal reconciliation.
- [ ] Extract recovery/resume behavior.
- [ ] Extract QUERY behavior.
- [ ] Extract ANSWER behavior.
- [ ] Extract terminal response behavior.
- [ ] Extract telemetry.
- [ ] Reduce `ValidatedAgentLoop` to a thin coordinator without changing its public construction boundary unless composition wiring is updated in the same commit.
- [ ] Keep `answering` free of dependencies on other Modulith modules.

### Review targets

These are review thresholds rather than hard automated limits:

- `ValidatedAgentLoop`: preferably no more than roughly 350 lines
- No extracted coordinator should require the full original dependency set
- No circular dependency between execution collaborators
- No collaborator may inspect persistence implementation types

## 8. Phase 3 — split `Lsp4jJavaSemanticService`

### Problem

`Lsp4jJavaSemanticService` currently implements five semantic operations while also owning document lifecycle, call-hierarchy requests, definition fallback, implementation lookup, document-symbol traversal, source reads, URI checks, deduplication, and conversion from LSP4J values to domain records.

### Target package structure

Keep the public adapter facade and all protocol types under the existing allowed adapter package:

```text
semantic/adapter/jdtls/
  Lsp4jJavaSemanticService.java

semantic/adapter/jdtls/protocol/
  JdtCallHierarchyClient.java
  JdtDefinitionClient.java
  JdtImplementationClient.java
  JdtDocumentScope.java

semantic/adapter/jdtls/mapping/
  JdtSymbolResolver.java
  LspSemanticMapper.java
```

The exact split may be smaller if a proposed class would be trivial. Prefer cohesive objects over one helper per method.

### Required design

#### Facade

`Lsp4jJavaSemanticService` remains the only implementation of `JavaSemanticService` and delegates each semantic operation to internal collaborators.

#### Document scope

`JdtDocumentScope` should centralize:

- Local-source validation
- Source reading
- `didOpen`
- The supplied semantic query
- `didClose`
- Cleanup failure behavior and session invalidation

The current behavior that preserves a primary failure and attaches a sanitized cleanup failure must remain covered by tests.

#### Protocol clients

Protocol clients should own request construction and null-safe response handling for their specific LSP operations. They must not return raw LSP4J types outside `semantic.adapter.jdtls..`.

#### Symbol/mapping helpers

Symbol traversal and conversion to `SemanticMethod`, `SemanticCall`, `SemanticIncomingCall`, and related domain records should be isolated from transport invocation where practical.

### Required changes

- [ ] Add or retain tests for exact method resolution.
- [ ] Add or retain tests for outgoing and incoming call conversion.
- [ ] Add or retain tests for definition fallback and ambiguity.
- [ ] Add or retain tests for implementation lookup.
- [ ] Add or retain tests for `didClose` cleanup failure and session invalidation.
- [ ] Keep the existing ArchUnit confinement rules green.
- [ ] Keep `JavaSemanticService` free of LSP4J, JDT, filesystem, and API DTO types.

### Review targets

- `Lsp4jJavaSemanticService` should read as a facade rather than a protocol implementation monolith.
- LSP operation names and request construction should live close to the protocol client that owns them.
- Conversion logic should be testable without launching a real JDT LS where possible.

## 9. Phase 4 — safely reduce `DefaultJdtWorkspaceManager`

### Problem

`DefaultJdtWorkspaceManager` combines workspace registry ownership, process launch, startup cleanup, capacity eviction, maintenance retries, shutdown, invalidation, metrics, gauges, and several concurrency protocols.

This class is oversized, but indiscriminate splitting would be more dangerous than leaving it intact because its maps and locks form one lifecycle aggregate.

### Required extraction order

Extract only low-risk responsibilities first:

1. Pure eviction/candidate selection
2. Process launch command and launch-failure normalization
3. Process termination operation and termination-result classification
4. Metrics/gauge binding

Only after those extractions are stable may the registry itself be moved to a separate object.

### Recommended structure

```text
semantic/adapter/jdtls/workspace/
  DefaultJdtWorkspaceManager.java
  JdtWorkspaceRegistry.java
  JdtWorkspaceLauncher.java
  JdtWorkspaceTerminationCoordinator.java
  JdtWorkspaceEvictionPolicy.java
  JdtWorkspaceMetricsBinder.java
```

`JdtWorkspaceRegistry` is optional. When introduced, it must be the sole owner of:

- `sessions`
- `launchingProcesses`
- `transientStatuses`
- Registry mutation synchronization

Do not inject the mutable maps into several Spring beans.

### Locking rules

- Preserve the current lifecycle lock purpose and acquisition boundaries.
- Preserve the process-registry synchronization boundary.
- Document any required lock ordering in code before moving lock-owning methods.
- Do not hold a newly introduced lock while invoking an external process or waiting on JDT LS unless that wait is already part of the current lifecycle protocol.
- Shutdown must still reject new work before attempting bounded lock acquisition and process cleanup.

### Required changes

- [ ] Extract at least process launch/termination or eviction policy from the manager.
- [ ] Keep exactly one mutable registry owner.
- [ ] Keep startup cancellation, retained launch reconciliation, idle eviction, demand eviction, and shutdown race tests green.
- [ ] Add a focused test for any newly introduced registry or policy object.
- [ ] Keep `JdtWorkspaceSession` as the concurrency aggregate for one workspace; do not distribute its activity, document-lock, and eviction state across services.

### Review target

The manager should remain the lifecycle facade, but its methods should delegate stateless mechanics instead of containing every process and policy detail.

## 10. Architecture-test requirements

### Root Agent

Extend existing Modulith/ArchUnit tests to enforce:

- `answering` still has no module dependency.
- `interaction.port.out..` does not depend on `answering.port.in..`.
- `persistence` no longer declares `answering :: port-in` after Phase 1.
- Extracted `answering.application.execution..` classes remain module-internal.
- Persistence adapters do not import `answering.application..` or `interaction.application..`.

### Java Semantic Service

Retain and, where useful, strengthen rules that enforce:

- LSP4J types stay inside `semantic.adapter.jdtls..`.
- Eclipse JDT types stay inside the two approved adapter trees.
- `semantic.domain` and `semantic.application` remain protocol-library free.
- HTTP API packages do not depend on JDT LS adapter types.
- Call-graph application code does not depend on repository adapters.

## 11. Commit plan

Use small behavior-preserving commits. A recommended sequence is:

1. `test: characterize architecture refactor boundaries`
2. `refactor: decouple interaction persistence result`
3. `refactor: extract agent run recovery coordination`
4. `refactor: extract query action execution`
5. `refactor: extract answer and terminal execution`
6. `refactor(semantic): extract JDT protocol clients`
7. `refactor(semantic): extract workspace lifecycle mechanics`
8. `test: strengthen modularity and adapter boundaries`
9. `docs: update architecture after refactor`

Do not combine broad package moves with unrelated formatting or behavior changes.

## 12. Follow-up work not required for this PR

### Shared call-graph assembly

`IncomingSemanticCallGraphBuilder` and `SemanticCallGraphBuilder` each maintain a large nested build state with overlapping node registration, edge deduplication, depth budgeting, deterministic ordering, warning normalization, and fragment materialization.

A follow-up should extract only common assembly mechanics, for example:

```text
callgraph/application/assembly/
  GraphAssemblyState.java
  GraphNodeRegistry.java
  GraphDiagnostics.java
  GraphOrderings.java
  DepthBudget.java
```

The incoming and outgoing traversal algorithms should remain separate. Do not introduce an abstract builder dominated by direction conditionals or callbacks.

### Rename `trie` to an API-routing capability

`com.java.semantic.trie` represents an API-routing capability, not merely a data structure. A later package migration should prefer:

```text
com.java.semantic.apirouting.domain
com.java.semantic.apirouting.application
com.java.semantic.apirouting.adapter.repository
com.java.semantic.apirouting.internal.trie
```

Keep the actual trie node implementation internal while naming the public package after the business capability.

### Other candidates

After the merge-blocking work is stable, reassess:

- `DirectCallRelationshipResolver`
- `ClassMetadataExtractor`
- `JavaSemanticResultMapper`
- `JavaSemanticServiceHttpAdapter`
- `PostgresSessionInboxAdapter`

Large reducers, transaction adapters, and concurrency aggregates should not be split solely by line count. `AgentStateReducer`, `JdtWorkspaceSession`, and atomic JDBC adapters may remain comparatively large when their state invariants are clearer in one owner.

## 13. Definition of done

This PR is complete when:

- [ ] All merge-blocking phases are implemented or explicitly reduced in scope in the PR description.
- [ ] Both ordinary Maven suites pass.
- [ ] `ApplicationModularityTests` and `ArchitectureTest` pass.
- [ ] Relevant JDT LS integration tests pass when `JDTLS_HOME` is available.
- [ ] No HTTP, OpenAPI, database, action, retry, budget, cancellation, or revision semantics changed.
- [ ] The PR description lists the final package moves and validation commands actually run.
- [ ] Architecture documentation reflects the implemented structure rather than the initial proposal.
