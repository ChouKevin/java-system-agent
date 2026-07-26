# java-system-agent Business Map

> 更新時間：2026-07-27
> 來源：目前已實作的 root contracts；沒有 active external entrypoint inventory

## Overview

The root project currently provides a non-runnable validated Agent kernel, a durable source-message
inbox, and PostgreSQL persistence adapters. It has no active Slack listener, HTTP controller,
scheduler, worker, LLM adapter, semantic-query adapter, or response-delivery adapter.

The repository also contains `java-semantic-service/`, an independently built service that owns
repository lifecycle and Java semantic analysis. The two projects share only versioned HTTP
contracts and opaque `repoId` values.

## Business Groups

| Group name | Implemented responsibility | Keywords | Detail file |
|------------|----------------------------|----------|-------------|
| `repo-management` | Root-side repository catalog/revision contracts and the ownership boundary with the semantic service; no root lifecycle adapter | repository, repoId, catalog, revision, snapshot, clone, pull, checkout | [Repo 管理邊界](business-groups/repo-management.md) |
| `code-analysis` | Generic validated `QUERY` contract, evidence handling, and the absent semantic integration boundary | analysis, query, capability, candidate, evidence, observation, citation, uncertainty | [程式碼查詢邊界](business-groups/code-analysis.md) |
| `slack-agent` | Durable source-message/session flow, validated actions, recovery, and terminal persistence; no active Slack integration | session, inbox, thread, queue, retry, recovery, action, answer, clarify | [Session Agent 核心](business-groups/slack-agent.md) |

## Cross-Group Interactions

| From | To | Current relationship |
|------|----|----------------------|
| `slack-agent` | `code-analysis` | The runtime can validate a generic `QUERY`, but no production semantic-query adapter is wired |
| `repo-management` | `code-analysis` | Repository catalog and exact revision are runtime contracts; the semantic service owns actual source lifecycle |

## Current External Entry Points

None in the root Agent. M1 exposes Java ports only. The Spring application has no composition root
that connects source ingestion, inbox processing, the validated loop, semantic service, or response
delivery.

## Contract Quick Reference

| Group name | Contract | Status |
|------------|----------|--------|
| `slack-agent` | `EnqueueSessionMessageUseCase.enqueue` | Inbound Java contract; no Slack adapter or caller |
| `slack-agent` | `AnswerQuestionUseCase.answer` | Inbound validated-loop contract; no worker wiring |
| `code-analysis` | `AgentSemanticQueryPort.query` | Outbound contract; test fake only |
| `repo-management` | `RepositoryCatalogPort` / `RepositoryRevisionPort` | Outbound contracts; test fakes only |
