# java-system-agent

This repository temporarily contains two independent Java 21 / Spring Boot projects:

- the root `java-system-agent`, a profiled production composition for a validated Agent with a durable session inbox and PostgreSQL persistence
- `java-semantic-service/`, the separately built service that owns repository lifecycle, JDT LS integration, and call-graph construction during its extraction transition

They share versioned HTTP contracts and an opaque `repoId`; there is deliberately no Maven
aggregator or shared Java library. The semantic service's canonical destination is
`git@github.com:ChouKevin/java-code-intelligence.git`. After the history-preserving extraction and
consumer cutover are verified, this repository will delete `java-semantic-service/` and retain only
the Agent-side HTTP client and service contract. See
[`docs/handoffs/java-code-intelligence-extraction.md`](docs/handoffs/java-code-intelligence-extraction.md).

## Current Status

The root Agent has a production composition graph when the `agent-runtime` profile is active.
That profile remains manually drivable: it starts no background work, so a caller must invoke its
Java inbound contracts. The `slack-agent` profile includes `agent-runtime` and adds mention-only
Slack Socket Mode admission, one Agent inbox worker, and one Slack-delivery worker.

The composed M3 flow provides:

- a framework-free validated loop whose model proposes exactly one `QUERY`, `ANSWER`, or `CLARIFY`
- deterministic validation of issued handles, schemas, revisions, budgets, cancellation, evidence,
  citations, and answer verdicts
- durable-before-ACK Socket Mode admission that canonicalizes source identity, detects payload
  conflicts, maps a source thread to one opaque `SessionId`, and keeps a source message on one
  stable `AnalysisRunId`
- one immediate receipt outbox message per accepted source event, followed by a final-response
  outbox message; Slack transport is at-least-once from this application's perspective
- participant-aware, append-only session history; global single inbox claiming with same-session
  ordering
- startup recovery of interrupted inbox/delivery claims, typed model-capacity deferral, a bounded
  three-attempt infrastructure retry policy, and terminal reconciliation
- append-only Agent events, an atomic current-state snapshot, and append-only accepted session turns
- versioned JSON codecs that reject unknown or malformed persistence documents
- PostgreSQL adapters for source admission, inbox, Agent transitions, cancellation, session history,
  and delivery outbox durability
- Spring AI action planning and answer-verification adapters, including a contract-only verifier
- HTTP repository catalog/revision and five Java Semantic Service capability adapters

The model chooses the semantic action, including candidate subset and order. Runtime validators
accept or reject that proposal against issued contracts. The reducer does not make semantic
decisions; it deterministically computes the next state from an accepted event, and the persistence
adapter atomically stores that exact event/state pair.

There is no confidence field, route score, or semantic ranking in this flow. Uncertainty remains
language and structured context: observations, evidence, warnings, candidate descriptions, and the
model's explicit explanation. The runtime does not truncate, summarize, delete, reorder, or rewrite
conversation or model-selected candidates.

## Durable Slack Flow

```text
supported Slack app_mention
  -> normalize and durably admit canonical source event
  -> atomically create inbox row and receipt-delivery outbox row
  -> ACK Socket Mode only after durable admission
  -> delivery worker sends receipt
  -> inbox worker globally claims an eligible session head
  -> validated Agent loop: propose, validate, query/answer/clarify, reduce
  -> atomically append Agent event and replace current snapshot
  -> append participant-aware immutable accepted session turn
  -> complete inbox and create final-delivery outbox row
  -> delivery worker sends final response after its receipt
```

The single-process deployment uses a PostgreSQL global inbox claim gate; messages in the same session
cannot pass an earlier `PENDING` or `PROCESSING` message. A typed model-capacity deferral returns the
message to `PENDING` at the supplied retry time without consuming another external attempt. A failed
infrastructure attempt returns the same message to `PENDING` with backoff. After three external
attempts, the inbox schedules a fourth terminal-reconciliation claim. On startup, interrupted
inbox and delivery claims can be returned to `PENDING` without changing durable identity. Attempts
two and three may restart a nonterminal Agent attempt through reducer events and freshly issued
context. If no run state was persisted yet, the inbox attempt seeds the initial Agent attempt
sequence. After bootstrap, that sequence is persisted in run state and advances only through reducer
events, including revision-driven restarts.
A recovered claim beyond the configured ceiling
is terminal-reconciliation-only: it may finish an already durable terminal result, but it cannot
call the model, semantic provider, or verifier again. A safely persisted nonterminal run concludes
with Agent outcome `FAILED` while the inbox becomes `COMPLETED`; inbox `FAILED` is reserved for
absent or unsafe state, or reconciliation failure.

Inbox completion is deliberately separate from terminal Agent persistence and session-turn append.
If the process stops between those boundaries, retry uses the same run ID: persisted terminal state
prevents another model action, and the immutable `(sessionId, runId)` turn append becomes a no-op
when its content is identical.

Receipt delivery is a predecessor of final delivery. The `slack-agent` `SmartLifecycle` manager
recovers interrupted work before starting its two fixed-delay polling loops; on shutdown it first
closes claim admission, then waits for the configured grace period. These guarantees target one
machine and one process. PostgreSQL transactions protect implemented atomic boundaries, but the
Agent does not claim distributed ownership, leases, or cross-machine coordination.

When an answer is accepted in `contract-only` mode, it is a `COMPLETED` answer and the inbound
`AnswerQuestionResult` retains `responseKind=ANSWER` and
`verificationBasis=CONTRACT_ONLY`. This is deliberately not a fabricated LLM verdict; callers can
distinguish it from an LLM-verified answer through the typed result.

## Project Layout

```text
src/main/java/com/java/system/agent/
  Application.java
  runtime/       validated action-loop domain, application flow, and ports
  inbox/         durable source-message queue contracts and processing policy
  persistence/   versioned JSON codecs and PostgreSQL JDBC adapters
  capability/    fixed catalog, executor registry, and generic QUERY dispatcher
  codebase/      Java Semantic Service HTTP adapter and five read-only executors
  model/         Spring AI action and answer-verification adapters
  Agent*Configuration.java
                 profile-gated root composition and replaceable infrastructure
  slack/         Socket Mode source normalization and Slack delivery transport
  worker/        inbox and delivery SmartLifecycle polling

src/main/resources/db/migration/
  V1__create_agent_session_inbox_and_trace.sql
  V2__create_slack_source_and_delivery_lifecycle.sql

java-semantic-service/
  pom.xml        temporary embedded location of the independent semantic service

knowledge/
  service-map.md
  repos/{repoId}/
```

`runtime` and `inbox` contain no Spring components or JDBC code. `runtime` has no module
dependencies and remains framework-free; its reducer only computes the next state from an accepted
event. The root configuration is the privileged composition boundary.

## Running the Production Composition

Activate `agent-runtime` only when a manual driver will use the Java contracts and the required
external services are available. It creates no Socket Mode connection or background worker:

```bash
export SPRING_PROFILES_ACTIVE=agent-runtime
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/agent
export SPRING_DATASOURCE_USERNAME=agent
export SPRING_DATASOURCE_PASSWORD=secret
export GOOGLE_API_KEY=...
export GOOGLE_GENAI_MODEL=gemini-3.1-flash-lite
export CODEBASE_SERVICE_BASE_URL=http://localhost:8081
export CODEBASE_SERVICE_API_TOKEN=...
export AGENT_ANSWER_VERIFICATION_MODE=llm  # or contract-only
```

To run the Slack integration, activate `slack-agent`; its profile group also activates
`agent-runtime`. Slack requires an app-level Socket Mode token and a bot token, plus the bot user ID
unless `auth.test` can resolve it at startup:

```bash
export SPRING_PROFILES_ACTIVE=slack-agent
export SLACK_APP_TOKEN=xapp-...
export SLACK_BOT_TOKEN=xoxb-...
export SLACK_BOT_USER_ID=U...  # optional when Slack auth.test is available
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/agent
export SPRING_DATASOURCE_USERNAME=agent
export SPRING_DATASOURCE_PASSWORD=secret
export GOOGLE_API_KEY=...
export CODEBASE_SERVICE_BASE_URL=http://localhost:8081
export CODEBASE_SERVICE_API_TOKEN=...
```

With the profile off, the Agent persistence composition does not create or access a database. With
it on, the project-owned default is an unpooled `DriverManagerDataSource` plus Flyway migration; an
integrator may instead provide `DataSource`, `Flyway`, `JdbcClient`, or `TransactionTemplate` beans.
The default model is [`gemini-3.1-flash-lite`](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite).

The five built-in, read-only codebase capabilities are:

- `codebase.list-entry-points`
- `codebase.lookup-api-route`
- `codebase.suggest-api-route`
- `codebase.outgoing-call-graph`
- `codebase.incoming-call-graph`

A representative one-query answer performs three LLM calls and three HTTP calls: catalog HTTP →
`QUERY` action LLM → revision HTTP → capability HTTP → `ANSWER` action LLM → verifier LLM. The
model may make more than one `QUERY`, so actual calls can be higher.

For planning only, one Gemini envelope is 15 RPM, 250,000 input TPM, and 500–1,500 RPD. These
numbers are project/model/tier-specific, are not guaranteed, and active limits in AI Studio are
authoritative; see the [Gemini rate-limit documentation](https://ai.google.dev/gemini-api/docs/rate-limits).
A single question can consume several requests. A provider capacity signal produces a typed inbox
deferral; other transient infrastructure failures use the durable retry/backoff policy.

## Build and Test

Run the normal root suite without Docker:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml clean test
```

Run the focused module-boundary and Slack lifecycle checks:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml test \
  -Dtest=ApplicationModularityTests,RuntimeKernelArchitectureTest,InboxModuleArchitectureTest,PersistenceModuleArchitectureTest
```

The normal suite covers Socket Mode admission, source/inbox/delivery contracts through lightweight
fakes, and `SmartLifecycle` worker recovery and shutdown without requiring Docker or a live Slack
workspace. The PostgreSQL profile below verifies the real database boundaries.

Run PostgreSQL migrations and adapter integration tests through Testcontainers:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml -Ppostgres-it verify
```

This profile requires a working Docker daemon. The normal root test suite does not.

Build the semantic service separately:

```bash
mvn -f java-semantic-service/pom.xml clean test
```

## Extension Boundary

New read-only capabilities fit the existing generic `QUERY` contract: issue an opaque capability
handle and schema, let the model select it, then validate before calling its adapter. Examples
include Java semantic analysis, API reads, and log queries.

An action that can modify external state must not be disguised as `QUERY`. It requires a deliberate
future `EXECUTE` contract with authorization, approval, idempotency, side-effect audit, and
result-reconciliation rules. M3 implements no such action.

## Business Knowledge

`knowledge/` is agent-owned, hand-authored documentation. It is separate from mutable source clones
under `repos/`, is never generated automatically, and must be maintained when behavior changes.
Start with [`knowledge/service-map.md`](knowledge/service-map.md), then follow the selected
repository's business map and group documents.
