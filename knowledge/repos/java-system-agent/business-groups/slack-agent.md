# Slack 助理

## Business Purpose

This group handles a Slack @mention from arrival to streamed reply: deduplicate the event, rate-limit the user, run the LLM agent loop with document and analysis tools, verify the answer fail-closed, and stream the reply back into the thread.

## Entry Points

| Package | Class | Method | When to inspect |
|---------|-------|--------|-----------------|
| `com.java.system.agent.slack.listener` | `SlackEventListener` | `processAppMention` | User asks what happens after the bot is mentioned. |

## Required Input Data

| Field | Meaning |
|-------|---------|
| `event` | Slack app-mention event containing the user question. |
| `threadTs` | Thread identifier; keys the per-thread conversation memory. |
| `eventId` | Used to deduplicate redelivered Slack events. |

## System Behavior

1. Drop duplicate events and rate-limited users before any LLM call.
2. Run the outer business-analyst loop: read service map and business documents, pick a repo and entry point.
3. Call the analysis tools; an inner translator loop renders the call graph in business language.
4. Verify the answer fail-closed; unverified answers are flagged instead of silently returned.
5. Stream the reply into the Slack thread and record the decision trace.

## Related Dependencies

| Dependency | Role |
|------------|------|
| `SlackEventDeduplicator` | Drops redelivered events. |
| `AgentLoopRunner` | Shared loop machinery for both LLM layers. |
| `DocumentTools` / `AgentAnalysisTools` | Tools the LLM uses to read documents and run analysis. |
| `SlackStreamClient` | Streams partial replies into the thread. |

## Source Lookup

Use `find_call_graph` with:

| repoId | packageName | className | methodSignature |
|--------|-------------|-----------|-----------------|
| `java-system-agent` | `com.java.system.agent.slack.listener` | `SlackEventListener` | `processAppMention` |
