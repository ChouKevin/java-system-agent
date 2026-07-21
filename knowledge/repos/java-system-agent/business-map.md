# java-system-agent Business Map

## Overview

This repository is the Slack AI agent itself: it answers business questions about Java services by running static call-graph analysis and translating the result into business language. It also serves as the committed demo repository for the documentation layout. Use this file to choose a business group, then read the matching group document for entry point details.

## Business Groups

| Group name | Description | Detail file |
|------------|-------------|-------------|
| `repo-management` | Git repository lifecycle: clone, pull, checkout, branch query, and startup cache warming. | [Repo 管理](business-groups/repo-management.md) |
| `code-analysis` | Static call-graph analysis of Java methods, either by method coordinates or by API path lookup. | [程式碼分析](business-groups/code-analysis.md) |
| `slack-agent` | Slack @mention Q&A: LLM agent loop, tool calls, verification, and streamed replies. | [Slack 助理](business-groups/slack-agent.md) |

## Cross-Group Interactions

| From | To | Reason |
|------|----|--------|
| `slack-agent` | `code-analysis` | The agent loop calls the analysis tools to answer code questions. |
| `repo-management` | `code-analysis` | Git mutations invalidate and reload per-repo analysis caches. |

## 進入點快速對照表

| Group name | Class | Method |
|------------|-------|--------|
| `repo-management` | `com.java.system.agent.api.RepoController` | `pullRepo` |
| `code-analysis` | `com.java.system.agent.api.CallGraphController` | `getCallGraph` |
| `slack-agent` | `com.java.system.agent.slack.listener.SlackEventListener` | `processAppMention` |
