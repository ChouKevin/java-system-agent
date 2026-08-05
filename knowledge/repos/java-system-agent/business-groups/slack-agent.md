# Session Agent 核心

## Business Purpose

此群組實作來源訊息進入 durable inbox 後的 session ordering、validated Agent loop、
terminal persistence 與 restart recovery。來源可以是未來的 Slack adapter，也可以是其他
transport；runtime 不知道 Slack thread 格式，只接收 opaque source reference 與
`SessionId`。

## Current External Entry Points

None. 舊的 `SlackEventListener`、event deduplicator、stream client 與 chat-memory endpoints
已不存在。`agent-runtime` profile 已組裝 Java use-case、Google Gemini action/verifier、Java
Semantic HTTP、PostgreSQL 與 inbox processor，但尚無 Slack ingress、scheduler、worker 或
Slack response delivery。

## Required Input

| Value | Meaning |
|-------|---------|
| `SessionSourceRef` | Transport type 與來源 thread key；持久層將它解析成 opaque session |
| `SourceMessageId` | 來源訊息去重 identity |
| `exactQuestion` | 不截斷、不摘要、不改寫的使用者問題 |
| `AttemptBudget` | 每個 run 可消耗的 action/query/revision 等上限 |

## Implemented Flow

1. `enqueue` 原子解析或建立 session、依 source message 去重，並配發 session sequence、inbox
   ID 與 stable run ID。
2. `claimNext` 只認領到期 session head；同 session 後續訊息不能越過較早的 `PENDING` 或
   `PROCESSING`，不同 session 可獨立認領。
3. `ValidatedAgentLoop` 讀取累積 session history，讓 LLM 提出一個 action，再以 deterministic
   validators 接受或拒絕；query capability 經由 Java code intelligence service HTTP adapter 執行。
4. Reducer 只依 accepted event 計算下一 state；transition adapter 在一個 transaction 內
   append event 並 CAS current snapshot。
5. Terminal answer/clarification 通過 cancellation arbitration 後，append 一筆 immutable
   session turn，再完成 inbox。
6. 若在 durable terminal/turn 後、inbox completion 前中斷，startup recovery 將
   `PROCESSING` 退回 `PENDING`。相同 run retry 讀到 terminal state，不再呼叫 LLM；相同 turn
   append 是 no-op。
7. 基礎設施失敗採 exponential backoff，預設三次外部嘗試後安排第四次
   terminal-reconciliation claim。第 2、3 次可透過 reducer event
   重啟 nonterminal attempt 並重新配發 context。若 bootstrap 尚未持久化，inbox attempt
   會作為初始 Agent attempt 序號；之後序號保存在 run state，只由 reducer event 推進。
   若 answer 已持久化為 pending verification，recovery 只重試 verifier，不會再次 plan 或
   查詢 capability。
   第四次 terminal-reconciliation claim 只能
   reconcile 已 durable 的 terminal response，不能再呼叫 LLM、semantic provider 或
   verifier。若 run 已安全持久化但仍 nonterminal，Agent 以 `FAILED` 結束且 inbox 為
   `COMPLETED`；只有狀態缺失、不安全或 reconciliation 失敗時 inbox 才為 `FAILED`，同 session
   下一筆才可繼續。

`AnswerQuestionResult` 以 typed response kind 區分 answer、clarification 與 runtime notice；
answer 另帶 verification basis。`CONTRACT_ONLY` 是完成回答的明示合約依據，並非虛構 LLM verdict。

## Durable Data

| Data | Rule |
|------|------|
| `session_inbox` | Source-message dedup、stable run identity、per-session sequence 與 retry state |
| `agent_run_event` | Append-only、按 state revision 排序的事件 trace |
| `agent_run.current_state` | 與事件原子提交的 versioned current snapshot |
| `session_turn` | Accepted user/assistant turn；以 `(sessionId, runId)` immutable/idempotent |

## Current Limitation

保證範圍是單一機器 lifecycle。PostgreSQL transaction/locks 維護已實作的原子性與 session
ordering，但沒有 distributed worker lease、跨機 owner election 或 exactly-once response
delivery。
