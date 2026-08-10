# Repository Guidelines

## Project Structure & Module Organization

This repository holds one Maven project: the Agent. Java code intelligence is an external service
owned by `git@github.com:ChouKevin/java-code-intelligence.git`. The repositories share only
versioned HTTP contracts and an opaque `repoId`; do not introduce a shared Java library.

**Root project — the Agent.** A Spring Boot 4 / Java 21 Spring Modulith application.
`src/main/java/com/java/system/agent/` contains `Application.java`, eight Modulith modules, and
profile-gated root configuration:

```
answering/
  domain/       action/ answer/ candidate/ capability/ conversation/ evidence/ handle/
                observation/ run/ scope/
  application/  AnalysisApplicationService request/result boundary
                loop/ framework-free Agent lifecycle orchestration
                state/ deterministic state reduction and transition persistence
                validation/ action, evidence, and answer contract validation
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
                Java Semantic Service HTTP adapter, registry-contributed read-only capabilities, and executors
model/          Spring AI schema, callback, message, action, and answer-verification adapters
Agent*Configuration.java
                `agent-runtime` composition, properties, and replaceable infrastructure
slack/           Socket Mode source normalization and Slack delivery transport
worker/          profile-gated interaction and delivery lifecycle polling
```

`answering.domain` groups immutable action-loop values. `answering.application.loop` owns the
framework-free lifecycle kernel, with `ValidatedAgentLoop` as its only orchestrator;
`AnalysisApplicationService` remains the public request/result mapping boundary outside that
kernel. Loop code reaches model, persistence, capability, and verification infrastructure only
through `answering.port.in` and `answering.port.out` contracts.
Session history is read once and append-only, while the append-only Agent event trace and atomic
current-state snapshot are persisted separately. `interaction` serializes work by opaque session;
`persistence` implements infrastructure ports without becoming a named interface. `capability`
depends on `answering :: domain` and `answering :: port-out`; it owns the core ANSWER/CLARIFY
planning tools. `codeintelligence` contributes a registry-contributed read-only QUERY capability set and additionally
consumes the capability executor SPI and planning contract. `model` owns the Spring AI schema,
callback, and message adapters while consuming answering contracts and `capability :: planning`.

`issuedCapabilities` is answering's capability/handle catalog, not the current Spring AI callback
list. Before each action-model turn, the registry filters contributed registrations through
`PlanningToolRegistration.isIssued(context)` into one snapshot: the model may call only those
names, and the prompt names and callbacks are projected from that same snapshot. Provider follow-up
candidates carry canonical payload and analyzed-revision scope; historical evidence provenance is
context, not permission to repeat a tool. Follow-ups are trusted target/recommendation candidates,
not universal prerequisites: current revision-pinned direct candidates can project safe method,
type, and source targets through runtime-owned typed inputs. Identity tools that require a
provider-issued identity remain follow-up-required. Semantic method navigation can reach fields on
its owning type through a provider-issued type-member follow-up, and a typed field result can
authorize a provider-issued internal-reference search. Active candidate expiry, accumulated
candidate history, and session compaction remain deferred concerns; this repository does not yet
implement those future lifecycle designs.

The external `java-code-intelligence` service owns repository lifecycle, JDT LS integration,
call-graph construction, its HTTP/MCP adapters, build, deployment, and service documentation. This
repository retains only the Agent HTTP consumer, opaque `repoId`, revision-pinned contracts, and
consumer tests. Do not restore embedded service source or maintain dual copies.

`java-agent-starter` owns the local integration composition. It clones `java-system-agent` and
`java-code-intelligence`, builds both applications, and supplies their shared network and runtime
configuration for API integration development. Source ownership, builds, tests, and release history
remain independent in the two service repositories; Starter must not become a shared-code module.

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
- Capability execution is read-only. `codeintelligence` contributes its registry-defined QUERY
  capability set. Do not infer an external-state mutation contract.

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

## Working Documents

- `docs/new-agent-model-draft.md` — the Agent V2 architecture document. It records the decisions and their reasoning, and its section numbers are cited throughout the specs.
- `docs/superpowers/` — `specs/` for approved designs, `plans/` for implementation plans, `reviews/` for assessments. Reviews are dated artifacts: supersede them with a header, do not rewrite their findings.
- `.superpowers/sdd/progress.md` — a durable ledger of executed milestones, including defects found and adjudications made. It survives context compaction; trust it and `git log` over recollection.

**Documentation that states a false invariant is a defect.** Several Javadoc claims here have been withdrawn after review disproved them. Verify a claim against the code before writing it, and if the code contradicts a brief, report rather than document the brief.

## Build, Test, and Development Commands

Run from the repository root:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml clean test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml test -Dtest=ApplicationModularityTests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml -Ppostgres-it verify
```

The normal root suite requires no Docker or external service. The `postgres-it` profile uses
Testcontainers and requires Docker. `ApplicationModularityTests` exercises the Modulith contract;
the answering, interaction, and persistence architecture tests enforce their detailed package boundaries.

## Architecture Rules

These are enforced by tests, not convention:

- **`answering` depends on no other module.** Its `package-info.java` declares `@ApplicationModule(allowedDependencies = {})`, and `ApplicationModularityTests` asserts it has no direct dependencies.
- **`answering` exposes exactly three named interfaces**: `domain`, `port-in`, `port-out`, via `@NamedInterface(value = "domain", propagate = true)` and the two port packages. `application` and everything else stays module-internal.
- **`answering.domain` classes depend only on the JDK, their own packages, and minimal `com.fasterxml.jackson.annotation` metadata**; no other Jackson or framework package is allowed. Inbound and outbound ports depend only on the JDK and answering domain. `AnsweringKernelArchitectureTest` enforces all four rules.
- **`answering.application.loop` is the isolated lifecycle kernel.** It may depend only on the JDK,
  answering domain, state, validation, and inbound/outbound ports. It must not import Spring,
  adapters, root composition, or another Modulith module; `AnsweringKernelArchitectureTest`
  enforces the package location and dependency allow-list.
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

**Javadoc is written in Traditional Chinese with no trailing `。`** — a line break ends a sentence. Every class states what it is and where it sits in the flow.

Class names state their stage and role. The suffix vocabulary is fixed: `…Manager` owns a lifecycle, `…Evaluator` judges whether to stop, `…Planner` chooses the next action, `…Interpreter` translates an external response, `…Reducer` turns an event into state, `…Committer` persists, `…Policy` is a pure rule, `…Validator` asserts invariants.

Types are named by the lifecycle they belong to: `AnalysisRunId` and `RunOutcome` are run-scoped; `AgentRunState`, `RunAttempt`, and `AttemptBudget` are attempt-scoped.

**Do not create a Java package named `target`.** `.gitignore` carries a bare `target/` for Maven output, which silently ignores a package directory of that name at any depth. A `package-info.java` was lost this way once and survived only via `git add -f`.

**A word-boundary `sed` rename protects longer identifiers.** Renaming `Foo` with `\bFoo\b` deliberately leaves `FooTest` alone, so a rename plan must separately include the class declarations the boundary protected. Three test classes once ended up declaring a name that did not match their file, which compiles because Java enforces that only for `public` classes.

## Testing Guidelines

JUnit 5, AssertJ, and ArchUnit are the project test stack. Name tests `*Test`; every test class's
declared name must match its file name.

**Shared test fixtures live in two directories**, and scoped test commands must enumerate both explicitly: `src/test/java/com/java/system/agent/answering/adapter/fake/` and `src/test/java/com/java/system/agent/support/`. A command written as "everything except X" has missed them before.

**Test sources cross module boundaries freely.** A dependency check that greps only `src/main/java` will miss them, and "leaf-first deletion keeps the tree compiling" does not hold for tests.

## Commit & Pull Request Guidelines

Conventional Commits: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`. Keep commits small and behavior-focused.

Branch discipline is hook-enforced: `tmp/<task>` for short-lived work, squash-merged into its source; `feature/<name>` for longer work, merged with `--no-ff`. Everything else (`master`, `uat`, …) takes merges only. A `tmp/*` branch is never pushed.

PRs should state the problem and the observable behavior, list the verification commands run, and call out any effect on module boundaries, the Modulith contract, or the ArchUnit rules.
