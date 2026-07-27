# java-system-agent Business Map

> 更新時間：2026-07-27
> 來源：目前已實作的 production composition；沒有 active external entrypoint inventory

## Overview

The root project provides a profile-gated production composition for the validated Agent, durable
source-message inbox, PostgreSQL persistence, Spring AI action/verification, and Java Semantic
Service HTTP adapters. It still has no active Slack listener, HTTP controller, scheduler, worker,
or response-delivery adapter.

The repository also contains `java-semantic-service/`, an independently built service that owns
repository lifecycle and Java semantic analysis. The two projects share only versioned HTTP
contracts and opaque `repoId` values.

## Business Groups

| Group name | Implemented responsibility | Keywords | Detail file |
|------------|----------------------------|----------|-------------|
| `repo-management` | Root-side HTTP repository catalog/revision and ownership boundary with the semantic service | repository, repoId, catalog, revision, snapshot, clone, pull, checkout | [Repo 管理邊界](business-groups/repo-management.md) |
| `code-analysis` | Validated `QUERY`, evidence handling, fixed read-only capabilities, and Java Semantic HTTP execution | analysis, query, capability, candidate, evidence, observation, citation, uncertainty | [程式碼查詢邊界](business-groups/code-analysis.md) |
| `slack-agent` | Durable source-message/session flow, production action/verification, recovery, and terminal persistence; no active Slack integration | session, inbox, thread, queue, retry, recovery, action, answer, clarify | [Session Agent 核心](business-groups/slack-agent.md) |

## Cross-Group Interactions

| From | To | Current relationship |
|------|----|----------------------|
| `slack-agent` | `code-analysis` | The loop dispatches the model-selected read-only capability through one executor and the Java Semantic HTTP adapter |
| `repo-management` | `code-analysis` | HTTP catalog/revision establish opaque candidates and exact revisions; the semantic service owns actual source lifecycle |

## Current External Entry Points

None in the root Agent. `agent-runtime` composes Java inbound contracts and adapters, but there is
no controller, Slack ingress, listener, consumer, scheduler, worker, or response delivery.

## Contract Quick Reference

| Group name | Contract | Status |
|------------|----------|--------|
| `slack-agent` | `EnqueueSessionMessageUseCase.enqueue` | Inbound Java contract; no Slack adapter or caller |
| `slack-agent` | `AnswerQuestionUseCase.answer` | Inbound validated-loop contract; an external/manual driver is required |
| `code-analysis` | `CapabilityExecutionPort.execute` | Production dispatcher routes a selected capability to its sole executor/HTTP adapter |
| `repo-management` | `RepositoryCatalogPort` / `RepositoryRevisionPort` | Production Java Semantic Service HTTP adapter |
