# Repository Guidelines

## Project Structure & Module Organization

This repository holds **two independent Maven projects** that share only versioned HTTP contracts and an opaque `repoId`. There is deliberately no `<modules>` aggregation and no parent POM: without aggregation, introducing a shared library would require a dependency visible in a pom diff. Build them separately.

**Root project — the Agent.** A Spring Boot 4 / Java 21 Spring Modulith application.
`src/main/java/com/java/system/agent/` contains `Application.java`, eight Modulith modules, and
profile-gated root configuration:

```
answering/
  domain/       action/ answer/ candidate/ capability/ conversation/ evidence/ handle/
                observation/ run/ scope/
  application/  state/ validation/ + ValidatedAgentLoop and AnalysisApplicationService
  port/         in/ out/
interaction/
  domain/       immutable inbox message, source identity, status, and failure values
  application/  enqueue boundary, processor, and bounded retry policy
  port/         in/ out/
persistence/
  document/     versioned Agent state/event JSON codecs
  jdbc/         PostgreSQL inbox, transition, cancellation, and session adapters
capability/     framework-neutral PlanningToolProvider platform, registry, executor SPI, and generic QUERY dispatcher
codeintelligence/
                Java Semantic Service HTTP adapter, five read-only QUERY tools, and executors
model/          Spring AI schema, callback, message, action, and answer-verification adapters
Agent*Configuration.java
                `agent-runtime` composition, properties, and replaceable infrastructure
slack/           Socket Mode source normalization and Slack delivery transport
worker/          profile-gated interaction and delivery lifecycle polling
```

`answering.domain` groups immutable action-loop values. `ValidatedAgentLoop` is the only lifecycle
orchestrator; `AnalysisApplicationService` is only the public request/result mapping boundary.
Session history is read once and append-only, while the append-only Agent event trace and atomic
current-state snapshot are persisted separately. `interaction` serializes work by opaque session;
`persistence` implements infrastructure ports without becoming a named interface. `capability`
depends on `answering :: domain` and `answering :: port-out`; it owns the core ANSWER/CLARIFY
planning tools. `codeintelligence` contributes the five read-only QUERY tools and additionally
consumes the capability executor SPI and planning contract. `model` owns the Spring AI schema,
callback, and message adapters while consuming answering contracts and `capability :: planning`.

**`java-semantic-service/`** is the temporary embedded location of a standalone Java 21 / Spring Boot service that owns repository lifecycle, JDT LS integration, and call-graph construction. Its canonical destination is `git@github.com:ChouKevin/java-code-intelligence.git`. Until the history-preserving extraction is complete, it has its own `AGENTS.md`; read that before working in it. After consumer cutover, remove this directory from the Agent repository rather than maintaining duplicate service sources. The retained procedure is `docs/handoffs/java-code-intelligence-extraction.md`.

**`knowledge/`** holds hand-authored business documentation (`service-map.md`, `repos/{repoId}/business-map.md`, `summary.md`, `business-groups/*.md`). It is an asset in its own right and is never generated from source. **`repos/`** holds runtime clones and is never committed — anything hand-authored beside a clone is destroyed by the next `git pull`.

## Current State

M3 has a production composition graph behind the `agent-runtime` profile. It wires Spring AI action
planning and verification, the Java Semantic Service HTTP adapter, the built-in capability catalog
and dispatchers, HTTP repository catalog/revision, PostgreSQL durability, and the inbound Agent
contracts. The `slack-agent` profile includes `agent-runtime` and adds Socket Mode source admission,
one Agent worker, and one Slack-delivery worker.

- When `agent-runtime` is inactive, the Agent persistence composition creates and accesses no
  `DataSource`, Flyway, `JdbcClient`, or `TransactionTemplate`; this does not constrain unrelated
  host application infrastructure.
- When active, the default persistence boundary is project-owned unpooled
  `DriverManagerDataSource` plus Flyway; hosts can replace `DataSource`, Flyway, `JdbcClient`, or
  `TransactionTemplate` beans.
- `agent-runtime` remains manually drivable and starts no background work. `slack-agent` admits
  supported `app_mention` events through Socket Mode, then starts exactly one inbox polling loop
  and one delivery polling loop through `SmartLifecycle`.
- PostgreSQL durably owns source admission, inbox rows, Agent state/events, append-only session
  history, and receipt/final delivery outbox rows. Slack transport is at-least-once from this
  application's perspective; the deployment contract remains one machine and one process.
- Capability execution is read-only. `codeintelligence` contributes the five built-in QUERY tools:
  list entry points, lookup/suggest API routes, and outgoing/incoming call graphs. Do not infer an
  external-state mutation contract.

The validated action-loop cutover is current:

- The model proposes exactly one `QUERY`, `ANSWER`, or `CLARIFY` action and chooses any subset and order of answering-issued capability and candidate handles.
- Deterministic validation rejects unknown, stale, out-of-scope, schema-incompatible, over-budget, uncited, or unsupported output before execution or persistence.
- Answering never adds, removes, replaces, or semantically ranks the model candidate list.
- Session history is append-only and trace is separate; the runtime never truncates, summarizes,
  deletes, reorders, or rewrites conversation.
- There is no confidence, route score, or ranking. Uncertainty is expressed through typed observations, evidence, warnings, candidates, and descriptions.

The durable session lifecycle is also current:

- A source thread maps to one opaque `SessionId`; each accepted source message keeps one stable
  `AnalysisRunId` across retries and restart recovery.
- Durable source admission canonicalizes source identity, detects replay-payload conflicts, creates
  the inbox row and immediate receipt outbox entry atomically, and preserves the exact question and
  participant. Same-session messages execute in sequence; a PostgreSQL global claim gate permits
  one inbox claim at a time.
- Startup recovery returns interrupted `PROCESSING` rows to `PENDING` without changing identity or
  attempt count. Infrastructure failures use bounded retry; after three external attempts the inbox
  schedules a fourth terminal-reconciliation claim.
- Inbox attempt one is `INITIAL`; attempts within the retry ceiling are `RETRY` and may restart a
  nonterminal Agent attempt only through reducer events. Before bootstrap is persisted, the inbox
  attempt seeds the initial Agent attempt sequence. After bootstrap, the sequence is persisted in
  `AgentRunState` and advances only through reducer events. A recovered claim beyond the ceiling is
  `TERMINAL_RECONCILIATION`: it may finish a durable terminal response but must not call model,
  semantic, or verifier ports. A safely persisted nonterminal run concludes Agent outcome `FAILED`
  while its inbox message becomes `COMPLETED`; inbox `FAILED` is reserved for absent or unsafe state,
  or reconciliation failure.
- A persisted pending answer-verification checkpoint resumes by calling only the verifier; it does
  not re-plan or re-execute a capability. Verifier unavailability is an inbox retry/backoff failure.
- Typed model-capacity deferral returns a claimed inbox row to `PENDING` for a later capacity resume
  without consuming another external attempt. Receipt delivery precedes final delivery; interrupted
  inbox and delivery claims are recovered before workers begin polling, and shutdown stops new
  claims before awaiting the configured grace period.
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
the answering, interaction, and persistence architecture tests enforce their detailed package boundaries.

## Architecture Rules

These are enforced by tests, not convention:

- **`answering` depends on no other module.** Its `package-info.java` declares `@ApplicationModule(allowedDependencies = {})`, and `ApplicationModularityTests` asserts it has no direct dependencies.
- **`answering` exposes exactly three named interfaces**: `domain`, `port-in`, `port-out`, via `@NamedInterface(value = "domain", propagate = true)` and the two port packages. `application` and everything else stays module-internal.
- **`answering.domain` classes depend only on the JDK, their own packages, and minimal `com.fasterxml.jackson.annotation` metadata**; no other Jackson or framework package is allowed. Inbound and outbound ports depend only on the JDK and answering domain. `AnsweringKernelArchitectureTest` enforces all four rules.
- **`interaction` depends only on `answering :: domain` and `answering :: port-in`.** It exposes `domain`,
  `port-in`, and `port-out`; its application code remains internal and framework-free.
- **`persistence` depends only on exposed answering and interaction contracts.** It exposes no named
  interface and never imports answering/interaction application internals.
- **The model chooses semantic action; answering validators enforce the contract.** The model chooses action,
  candidate subset/order, capability, and uncertainty wording from answering-issued opaque handles.
  Answering validation accepts or rejects catalog membership, schemas, revisions, budgets,
  cancellation, evidence citations, and verdicts.
- **`AgentStateReducer` makes no semantic choice.** It is the only type that deterministically turns
  an accepted event into the next `AgentRunState`. `AgentTransitionCommitter` persists that event
  and exactly its candidate state through one atomic port boundary. Answering lifecycle policy, not
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

**Two V2 test fixtures live in `answering/`**, at `src/test/java/com/java/system/agent/answering/adapter/fake/`. Scoped test commands must include this directory.

## Commit & Pull Request Guidelines

Conventional Commits: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`. Keep commits small and behavior-focused.

Branch discipline is hook-enforced: `tmp/<task>` for short-lived work, squash-merged into its source; `feature/<name>` for longer work, merged with `--no-ff`. Everything else (`master`, `uat`, …) takes merges only. A `tmp/*` branch is never pushed.

PRs should state the problem and the observable behavior, list the verification commands run, and call out any effect on module boundaries, the Modulith contract, or the ArchUnit rules.
