# Repository Guidelines

## Project Structure & Module Organization

This repository holds **two independent Maven projects** that share only versioned HTTP contracts and an opaque `repoId`. There is deliberately no `<modules>` aggregation and no parent POM: without aggregation, introducing a shared library would require a dependency visible in a pom diff. Build them separately.

**Root project — the Agent.** A Spring Boot 4 / Java 21 Spring Modulith application.
`src/main/java/com/java/system/agent/` contains `Application.java`, six Modulith modules, and
profile-gated root configuration:

```
runtime/
  domain/       action/ answer/ candidate/ capability/ conversation/ evidence/ handle/
                observation/ run/ scope/
  application/  state/ validation/ + ValidatedAgentLoop and AnalysisApplicationService
  port/         in/ out/
inbox/
  domain/       immutable inbox message, source identity, status, and failure values
  application/  enqueue boundary, processor, and bounded retry policy
  port/         in/ out/
persistence/
  document/     versioned Agent state/event JSON codecs
  jdbc/         PostgreSQL inbox, transition, cancellation, and session adapters
capability/     fixed catalog, executor SPI/registry, and generic QUERY dispatcher
codebase/       Java Semantic Service HTTP adapter and read-only executors
model/          Spring AI action and answer-verification adapters
Agent*Configuration.java
                `agent-runtime` composition, properties, and replaceable infrastructure
```

`runtime.domain` groups immutable action-loop values. `ValidatedAgentLoop` is the only lifecycle
orchestrator; `AnalysisApplicationService` is only the public request/result mapping boundary.
Session history is read once and append-only, while the append-only Agent event trace and atomic
current-state snapshot are persisted separately. `inbox` serializes work by opaque session;
`persistence` implements infrastructure ports without becoming a named interface. `capability`
depends on `runtime :: domain` and `runtime :: port-out`; `codebase` additionally consumes the
capability executor SPI; `model` has the same runtime-only dependencies as `capability`.

**`java-semantic-service/`** is a standalone Java 21 / Spring Boot service that owns repository lifecycle, JDT LS integration, and call-graph construction. It has its own `AGENTS.md`; read that before working in it.

**`knowledge/`** holds hand-authored business documentation (`service-map.md`, `repos/{repoId}/business-map.md`, `summary.md`, `business-groups/*.md`). It is an asset in its own right and is never generated from source. **`repos/`** holds runtime clones and is never committed — anything hand-authored beside a clone is destroyed by the next `git pull`.

## Current State

M2 has a production composition graph behind the `agent-runtime` profile. It wires Spring AI action
planning and verification, the Java Semantic Service HTTP adapter, the built-in capability catalog
and dispatchers, HTTP repository catalog/revision, PostgreSQL inbox/session/transition/cancellation
adapters, and `AnalysisApplicationService` / `SessionInboxProcessor`.

- When `agent-runtime` is inactive, the Agent persistence composition creates and accesses no
  `DataSource`, Flyway, `JdbcClient`, or `TransactionTemplate`; this does not constrain unrelated
  host application infrastructure.
- When active, the default persistence boundary is project-owned unpooled
  `DriverManagerDataSource` plus Flyway; hosts can replace `DataSource`, Flyway, `JdbcClient`, or
  `TransactionTemplate` beans.
- There is still no ingress: no HTTP controller, Slack listener, MQ consumer, scheduler, worker,
  or Slack response delivery. An external/manual driver must call inbound Java contracts and drive
  inbox processing.
- Capability execution is read-only. The five built-ins are list entry points, lookup/suggest API
  routes, and outgoing/incoming call graphs. Do not infer an external-state mutation contract.

The validated action-loop cutover is current:

- The model proposes exactly one `QUERY`, `ANSWER`, or `CLARIFY` action and chooses any subset and order of runtime-issued capability and candidate handles.
- Deterministic validation rejects unknown, stale, out-of-scope, schema-incompatible, over-budget, uncited, or unsupported output before execution or persistence.
- The runtime never adds, removes, replaces, or semantically ranks the model candidate list.
- Session history is append-only and trace is separate; the runtime never truncates, summarizes,
  deletes, reorders, or rewrites conversation.
- There is no confidence, route score, or ranking. Uncertainty is expressed through typed observations, evidence, warnings, candidates, and descriptions.

The durable session lifecycle is also current:

- A source thread maps to one opaque `SessionId`; each accepted source message keeps one stable
  `AnalysisRunId` across retries and restart recovery.
- The inbox preserves exact questions and source-message deduplication. Same-session messages execute
  in sequence; different sessions remain independently claimable.
- Startup recovery returns interrupted `PROCESSING` rows to `PENDING` without changing identity or
  attempt count. Infrastructure failures use bounded retry; after three failed attempts the message
  becomes `FAILED` and later session work is released.
- Inbox attempt one is `INITIAL`; attempts within the retry ceiling are `RETRY` and may restart a
  nonterminal Agent attempt only through reducer events. Before bootstrap is persisted, the inbox
  attempt seeds the initial Agent attempt sequence. After bootstrap, the sequence is persisted in
  `AgentRunState` and advances only through reducer events. A recovered claim beyond the ceiling is
  `TERMINAL_RECONCILIATION`: it may finish a durable terminal response but must not call model,
  semantic, or verifier ports for a nonterminal run.
- A persisted pending answer-verification checkpoint resumes by calling only the verifier; it does
  not re-plan or re-execute a capability. Verifier unavailability is an inbox retry/backoff failure.
- Agent event append and current-state replacement are one transaction. State/event JSON is
  versioned and decoded fail-closed. Accepted session turns are immutable and idempotent by
  `(sessionId, runId)`.
- These guarantees target one machine. Do not infer distributed ownership, leases, or cross-machine
  coordination from the PostgreSQL locking implementation.

## Build, Test, and Development Commands

Run from the repository root:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml clean test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml test -Dtest=ApplicationModularityTests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml -Ppostgres-it verify
mvn -f java-semantic-service/pom.xml clean test
```

The normal root suite requires no Docker or external service. The `postgres-it` profile uses
Testcontainers and requires Docker. `ApplicationModularityTests` exercises the Modulith contract;
the runtime, inbox, and persistence architecture tests enforce their detailed package boundaries.

## Architecture Rules

These are enforced by tests, not convention:

- **`runtime` depends on no other module.** Its `package-info.java` declares `@ApplicationModule(allowedDependencies = {})`, and `ApplicationModularityTests` asserts it has no direct dependencies.
- **`runtime` exposes exactly three named interfaces**: `domain`, `port-in`, `port-out`, via `@NamedInterface(value = "domain", propagate = true)` and the two port packages. `application` and everything else stays module-internal.
- **`domain` classes depend only on the JDK and their own packages**; inbound and outbound ports depend only on the JDK and domain. `RuntimeKernelArchitectureTest` enforces all four rules.
- **`inbox` depends only on `runtime :: domain` and `runtime :: port-in`.** It exposes `domain`,
  `port-in`, and `port-out`; its application code remains internal and framework-free.
- **`persistence` depends only on exposed runtime and inbox contracts.** It exposes no named
  interface and never imports runtime/inbox application internals.
- **The model chooses semantic action; validators enforce the contract.** The model chooses action,
  candidate subset/order, capability, and uncertainty wording from runtime-issued opaque handles.
  Runtime validation accepts or rejects catalog membership, schemas, revisions, budgets,
  cancellation, evidence citations, and verdicts.
- **`AgentStateReducer` makes no semantic choice.** It is the only type that deterministically turns
  an accepted event into the next `AgentRunState`. `AgentTransitionCommitter` persists that event
  and exactly its candidate state through one atomic port boundary. Runtime lifecycle policy, not
  the LLM, owns termination, revisions, and IDs.
- **Future read operations extend `QUERY`; mutations require a new contract.** Java analysis, API
  reads, and log queries can use capability schemas. Any future external-state change requires an
  explicit `EXECUTE` action with authorization, approval, idempotency, audit, and reconciliation;
  do not overload `QUERY`.
- **Inbound answers preserve their typed acceptance information.** `AnswerQuestionResult` and the
  internal `AgentLoopResult` return `RunResponseKind` plus optional `AnswerVerificationBasis`. A
  contract-only accepted answer is `COMPLETED` with `CONTRACT_ONLY`, not a synthetic LLM verdict.

## Coding Style & Naming Conventions

Four-space indentation and explicit Java types; **never use `var`**. Prefer records for immutable value objects, and put behavior on the type that owns the data. Avoid raw `== null` / `!= null` — use `Objects`, Spring assertions, or collection/string utilities. Use meaningful domain exceptions, never bare `RuntimeException`. Never inline a package name: use imports, not `new java.util.ArrayList<>()`.

**Javadoc is written in Traditional Chinese with no trailing `。`** — a line break ends a sentence. Every class states what it is and where it sits in the flow.

Class names state their stage and role. The suffix vocabulary is fixed: `…Manager` owns a lifecycle, `…Evaluator` judges whether to stop, `…Planner` chooses the next action, `…Interpreter` translates an external response, `…Reducer` turns an event into state, `…Committer` persists, `…Policy` is a pure rule, `…Validator` asserts invariants.

Types are named by the lifecycle they belong to: `AnalysisRunId` and `RunOutcome` are run-scoped; `AgentRunState`, `RunAttempt`, and `AttemptBudget` are attempt-scoped.

**Do not create a Java package named `target`.** `.gitignore` carries a bare `target/` for Maven output, which silently ignores a package directory of that name at any depth.

## Testing Guidelines

JUnit 5, AssertJ, and ArchUnit. Name tests `*Test`; every test class's declared name must match its file name.

Test behavior at domain-model boundaries rather than through scripted end-to-end walkthroughs. Where a rule lives in a pure function — `AgentActionValidator`, `AnswerDocumentValidator`, `RevisionVector.driftedFrom` — test it there and thoroughly. The loop gets only tests for observable port, persistence, revision, citation, and cancellation boundaries. A record whose constructor only calls `Objects.requireNonNull` does not need its own test class.

For adequately covered refactors, keep the relevant tests green rather than inventing a failing test. Use RED-GREEN for new observable behavior and public contract changes.

**Two V2 test fixtures live in `runtime/`**, at `src/test/java/com/java/system/agent/runtime/adapter/fake/`. Scoped test commands must include this directory.

## Commit & Pull Request Guidelines

Conventional Commits: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`. Keep commits small and behavior-focused.

Branch discipline is hook-enforced: `tmp/<task>` for short-lived work, squash-merged into its source; `feature/<name>` for longer work, merged with `--no-ff`. Everything else (`master`, `uat`, …) takes merges only. A `tmp/*` branch is never pushed.

PRs should state the problem and the observable behavior, list the verification commands run, and call out any effect on module boundaries, the Modulith contract, or the ArchUnit rules.
