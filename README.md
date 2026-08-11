# java-system-agent

This repository contains the Java 21 / Spring Boot Agent: a profiled production composition with a
validated action loop, durable session inbox, Slack integration, and PostgreSQL persistence.

Java repository lifecycle, JDT LS integration, and call-graph construction are owned by the
independent [`java-code-intelligence`](https://github.com/ChouKevin/java-code-intelligence)
service. The two repositories share only versioned HTTP contracts and an opaque `repoId`; there is
no Maven aggregation or shared Java library.

For local API integration development, `java-agent-starter` clones this repository and
`java-code-intelligence`, builds both services, and provides their shared network and runtime
configuration. Each service remains independently owned, built, tested, and versioned.

## Current Status

The root Agent has a production composition graph when the `agent-runtime` profile is active.
That profile remains manually drivable: it starts no background work, so a caller must invoke its
Java inbound contracts. The `slack-agent` profile includes `agent-runtime` and adds mention-only
Slack Socket Mode admission, one Agent inbox worker, and one Slack-delivery worker.

The composed Agent flow provides:

- a framework-free validated loop whose first model turn can only propose one `PLAN`; the runtime
  persists it immutably before the model may propose an issued `QUERY`, `ANSWER`, or `CLARIFY`
- deterministic validation of issued handles, schemas, revisions, budgets, cancellation, evidence,
  citations, exact plan-need resolution coverage, and answer verdicts
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
- HTTP repository catalog/revision and a registry-contributed Java Semantic Service read-only capability set

The model chooses the semantic action, including candidate subset and order. Runtime validators
accept or reject that proposal against issued contracts. The reducer does not make semantic
decisions; it deterministically computes the next state from an accepted event, and the persistence
adapter atomically stores that exact event/state pair.

There is no confidence field, route score, or semantic ranking in this flow. Uncertainty remains
language and structured context: observations, evidence, warnings, candidate descriptions, and the
model's explicit explanation. The runtime does not truncate, summarize, delete, reorder, or rewrite
conversation or model-selected candidates.

## Planning Capability Issuance

The registry contributes a read-only capability set. `issuedCapabilities` is answering's
capability/handle catalog, not the Spring AI callback list for the current turn. Before each action
model call, registrations are filtered by `PlanningToolRegistration.isIssued(context)` into the
current snapshot: the model may call only names in that snapshot, and prompt names and callbacks
come from that same snapshot.

Before a run has a question plan, the snapshot contains only `agent_plan_question`. Persisting its
`QuestionPlanCreated` event stores the ordered information needs, closes the planning phase, and
consumes one unified Agent step atomically. Later prompts put that immutable plan before current
evidence and append the action/result history. An `ANSWER` must resolve every plan need in order;
supported resolutions use current issued evidence that the answer cites, while unavailable
resolutions use current observations.

Provider follow-up candidates carry a canonical payload and the analyzed revision scope. Historical
evidence provenance remains context, not permission to repeat a tool. Semantic method navigation
can reach fields on its owning type through a provider-issued type-member follow-up; a typed field
result can authorize a provider-issued internal-reference search.

Follow-ups are trusted target or recommendation candidates, not universal prerequisites for every
semantic query. The model selects exactly one current candidate handle and only the safe typed
options exposed by that tool; direct method/type/source target projection remains runtime-owned and
revision-pinned. Tools whose identity must be provider-issued remain follow-up-required. Direct and
follow-up query modes consume the same existing query budget and rate limit. Within one pinned
attempt, a QUERY whose capability, candidate handles, and canonical payload already completed
successfully is rejected before another provider call or budget consumption; failed executions may
be retried, and a changed payload remains a new execution.

## Durable Slack Flow

```text
supported Slack app_mention
  -> normalize and durably admit canonical source event
  -> atomically create inbox row and receipt-delivery outbox row
  -> ACK Socket Mode only after durable admission
  -> delivery worker sends receipt
  -> inbox worker globally claims an eligible session head
  -> validated Agent loop: plan once, then propose, validate, query/answer/clarify, reduce
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
distinguish it from an LLM-verified answer through the typed result. Contract-only acceptance makes
no verifier call and creates no pending answer-verification checkpoint. In LLM mode, the verifier
receives a read-only view of the immutable question plan and the proposed resolutions.

## Project Layout

```text
src/main/java/com/java/system/agent/
  Application.java
  answering/
    domain/       immutable action-loop values, including plan and need-resolution contracts
    application/
      loop/       framework-free lifecycle kernel and ValidatedAgentLoop
      state/      deterministic reduction and transition persistence
      validation/ action, evidence, and answer contract validation
    port/         inbound and outbound contracts
  interaction/   durable source-message queue contracts and processing policy
  persistence/   versioned JSON codecs and PostgreSQL JDBC adapters
  capability/    planning-tool registry, executor SPI, and generic QUERY dispatcher
  codeintelligence/
                 external Java code intelligence HTTP adapter and registry-contributed read-only capabilities
  model/         Spring AI action and answer-verification adapters
  Agent*Configuration.java
                 profile-gated root composition and replaceable infrastructure
  slack/         Socket Mode source normalization and Slack delivery transport
  worker/        inbox and delivery SmartLifecycle polling

src/main/resources/db/migration/
  V1__create_agent_session_inbox_and_trace.sql
  V2__create_slack_source_and_delivery_lifecycle.sql

knowledge/
  service-map.md
  repos/{repoId}/
```

`answering` and `interaction` contain no JDBC code. `answering` has no module dependencies and
remains framework-free. `answering.application.loop` contains the lifecycle kernel and reaches
model, persistence, capability, and verification infrastructure only through answering ports;
`AnalysisApplicationService` remains the public request/result mapping boundary. Architecture
tests prevent the loop from depending on adapters, Spring, root composition, or another Modulith
module. The reducer only computes the next state from an accepted event, and root configuration is
the privileged composition boundary.

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

Java Semantic capabilities are contributed through the planning registry as a read-only capability
set. The action model receives only the registrations issued for its current context.

A representative one-query answer performs four LLM calls and three HTTP calls: catalog HTTP →
`PLAN` action LLM → `QUERY` action LLM → revision HTTP → capability HTTP → `ANSWER` action LLM →
verifier LLM. The model may make more than one `QUERY`, so actual calls can be higher.

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
  -Dtest=ApplicationModularityTests,AnsweringKernelArchitectureTest,InteractionModuleArchitectureTest,PersistenceModuleArchitectureTest
```

The normal suite covers Socket Mode admission, source/inbox/delivery contracts through lightweight
fakes, and `SmartLifecycle` worker recovery and shutdown without requiring Docker or a live Slack
workspace. The PostgreSQL profile below verifies the real database boundaries.

The standalone business-shaped acceptance fixtures can be compiled independently. For example:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q \
  -f src/test/resources/fixtures/payment-knowledge-query/pom.xml test
```

`PaymentKnowledgeLiveIT` uses the normal source-event and inbox path and is skipped unless the
Starter supplies its dedicated live environment. One test runs three isolated business-question
classes: source-answerable payment rules, current values owned by a runtime source, and a capability
absent from the repository. It asserts typed plans, resolutions, observations, citations,
provenance, and terminal outcomes rather than answer prose. Its fixture identities and payment rules
remain test resources and must not appear in production code or prompt resources.

Run PostgreSQL migrations and adapter integration tests through Testcontainers:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml -Ppostgres-it verify
```

This profile requires a working Docker daemon. The normal root test suite does not. Build and
deployment instructions for Java code intelligence belong to its independent repository.

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
