# java-system-agent

This repository contains two independent Java 21 / Spring Boot projects:

- the root `java-system-agent`, currently a validated Agent kernel with a durable session inbox and PostgreSQL adapters
- `java-semantic-service/`, the separately built service that owns repository lifecycle, JDT LS integration, and call-graph construction

They share versioned HTTP contracts and an opaque `repoId`; there is deliberately no Maven
aggregator or shared Java library.

## Current Status

The root Agent is intentionally **not runnable yet**. Its domain flow and PostgreSQL persistence
boundaries exist, but there is no composition root and no production wiring for Slack, LLM calls,
semantic queries, answer verification, repository catalogs/revisions, scheduling, or response
delivery. The Spring context therefore does not construct an Agent workflow.

What M1 provides:

- a framework-free validated loop whose model proposes exactly one `QUERY`, `ANSWER`, or `CLARIFY`
- deterministic validation of issued handles, schemas, revisions, budgets, cancellation, evidence,
  citations, and answer verdicts
- a durable inbox that maps a source thread to one opaque `SessionId` and a source message to one
  stable `AnalysisRunId`
- ordered processing within one session, while different sessions remain independently claimable
- startup recovery of interrupted `PROCESSING` messages and a bounded three-attempt infrastructure
  retry policy
- append-only Agent events, an atomic current-state snapshot, and append-only accepted session turns
- versioned JSON codecs that reject unknown or malformed persistence documents
- PostgreSQL adapters for inbox, Agent transitions, cancellation, and session history

The model chooses the semantic action, including candidate subset and order. Runtime validators
accept or reject that proposal against issued contracts. The reducer does not make semantic
decisions; it deterministically computes the next state from an accepted event, and the persistence
adapter atomically stores that exact event/state pair.

There is no confidence field, route score, or semantic ranking in this flow. Uncertainty remains
language and structured context: observations, evidence, warnings, candidate descriptions, and the
model's explicit explanation. The runtime does not truncate, summarize, delete, reorder, or rewrite
conversation or model-selected candidates.

## Durable Session Flow

```text
source message
  -> enqueue: resolve/create opaque session, deduplicate source message, allocate stable run
  -> claim: select an eligible session head
  -> validated Agent loop: propose, validate, query/answer/clarify, reduce
  -> atomically append Agent event and replace current snapshot
  -> append one immutable accepted session turn
  -> complete inbox message
```

Messages in the same session cannot pass an earlier `PENDING` or `PROCESSING` message. A failed
infrastructure attempt returns the same message to `PENDING` with backoff; after the third failed
attempt it becomes `FAILED`, allowing the next message in that session to proceed. On startup,
interrupted `PROCESSING` rows can be returned to `PENDING` without changing their session, run ID,
question, sequence, or attempt count. Attempts two and three may restart a nonterminal Agent attempt
through reducer events and freshly issued context. If no run state was persisted yet, the inbox
attempt seeds the initial Agent attempt sequence. After bootstrap, that sequence is persisted in run
state and advances only through reducer events, including revision-driven restarts.
A recovered claim beyond the configured ceiling
is terminal-reconciliation-only: it may finish an already durable terminal result, but it cannot
call the model, semantic provider, or verifier again; a nonterminal run becomes `FAILED`.

Inbox completion is deliberately separate from terminal Agent persistence and session-turn append.
If the process stops between those boundaries, retry uses the same run ID: persisted terminal state
prevents another model action, and the immutable `(sessionId, runId)` turn append becomes a no-op
when its content is identical.

These guarantees target the approved single-machine lifecycle. PostgreSQL transactions protect the
implemented atomic boundaries, but M1 does not claim distributed worker ownership, leases, or
cross-machine coordination.

## Project Layout

```text
src/main/java/com/java/system/agent/
  Application.java
  runtime/       validated action-loop domain, application flow, and ports
  inbox/         durable source-message queue contracts and processing policy
  persistence/   versioned JSON codecs and PostgreSQL JDBC adapters

src/main/resources/db/migration/
  V1__create_agent_session_inbox_and_trace.sql

java-semantic-service/
  pom.xml        independent semantic service build

knowledge/
  service-map.md
  repos/{repoId}/
```

`runtime` and `inbox` contain no Spring components or JDBC code. Persistence implementations also
carry no Spring stereotypes; a later composition root must construct them explicitly.

## Build and Test

Run the normal root suite without Docker:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml clean test
```

Run the focused module-boundary checks:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f pom.xml test \
  -Dtest=ApplicationModularityTests,RuntimeKernelArchitectureTest,InboxModuleArchitectureTest,PersistenceModuleArchitectureTest
```

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
result-reconciliation rules. M1 implements no such action.

## Business Knowledge

`knowledge/` is agent-owned, hand-authored documentation. It is separate from mutable source clones
under `repos/`, is never generated automatically, and must be maintained when behavior changes.
Start with [`knowledge/service-map.md`](knowledge/service-map.md), then follow the selected
repository's business map and group documents.
