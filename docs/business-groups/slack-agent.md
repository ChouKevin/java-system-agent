# slack-agent — 業務群組文件

> 所屬專案：java-system-agent
> 最後更新：2026-07-28 17:12
> 來源文件：business-scope.md
> 來源同步：2026-07-28 17:12

---

## 業務概述

`slack-agent` profile 包含 `agent-runtime`。Slack Socket Mode 只接收支援頻道中由真人發出的 `app_mention`；事件先被正規化為 canonical source、participant 與問題，再完成 PostgreSQL durable admission 才 ACK。admission 會依 transport event 與 canonical source identity 去重、偵測同 identity 的 payload conflict，並在同一交易建立 session、inbox 與立即 receipt delivery。

一個 `SmartLifecycle` manager 在啟動時先復原 interrupted inbox/delivery claims，之後維持一個 inbox 與一個 delivery 固定延遲輪詢迴圈。inbox 全域一次只認領一筆，且不會越過同 session 較早的工作；session history 以 participant-aware turn append-only 保留。模型容量不足會以 typed deferral 回到 `PENDING`，不消耗外部嘗試次數；其他基礎設施失敗依 bounded retry 處理，超過上限後進入 terminal reconciliation。

delivery outbox 先送 receipt，完成 Agent 後再送 final response。Slack transport 從本程式觀點為 at-least-once。關機時 manager 先停止新 claim，再取消輪詢並等待 grace period；未完成的 durable 工作由下次啟動復原。這些保證僅適用一台機器、一個 process。

---

## 進入點

### EP-001

類型：Slack Socket Mode 事件監聽
業務描述：接收並篩選 `app_mention`，將有效事件正規化並交給 durable source admission
負責業務：Slack 問答接收、來源正規化、durable admission
findCallGraph：
  packageName: com.java.system.agent.slack.source
  className: SlackAppMentionHandler
  methodSignature: apply
觸發方式：Slack Socket Mode `app_mention` 回呼；成功 durable admission 後 ACK，admission 失敗不 ACK
信心：✅
Side Effects：建立 canonical source/session/inbox/receipt delivery 的 PostgreSQL 交易

### EP-002

類型：背景工作 lifecycle
業務描述：啟動復原、單一 inbox claim 輪詢、delivery 輪詢與 graceful shutdown
負責業務：Agent 執行、收據與最終回覆投遞、recovery、shutdown
findCallGraph：
  packageName: com.java.system.agent.worker
  className: AgentWorkerManager
  methodSignature: start
觸發方式：`slack-agent` profile 的 `SmartLifecycle` auto-start；不是 `@Scheduled` job
信心：✅
Side Effects：復原 interrupted claims；收據先於 final response；停止新 claim 後等待 grace period

---

## 業務流程

1. [EP-001] Socket Mode 收到 `app_mention`。normalizer 僅保留支援 channel、真人、非 bot、含本 bot mention 且問題不為空的事件；其他事件直接安全 ACK。
2. [EP-001] 有效事件以 Slack workspace/channel/message/root timestamp 形成 canonical source identity，保留 participant 與原始文字。transport event 或 canonical identity 重送同一 payload 時取得既有 admission；相同 identity 的不同 payload 形成 durable conflict。
3. [EP-001] 新 admission 在同一 PostgreSQL 交易建立 session、順序 inbox row、stable run identity 與 receipt outbox，完成後 Socket Mode 才 ACK。
4. [EP-002] lifecycle start 先將 interrupted inbox/delivery claims 回到可處理狀態，再啟動一個 inbox 與一個 delivery 固定延遲迴圈。全域 inbox claim gate 使 process 同時只處理一筆 inbox 工作，並維持 session 的順序。
5. [EP-002] delivery worker 可送出 receipt；inbox worker 執行 validated Agent loop，將 Agent state/event 與 participant-aware append-only session turn 持久化。完成或安全失敗時寫入 final delivery；final delivery 受 receipt predecessor 約束。
6. [EP-002] 模型容量訊號將 inbox 延後到指定時間再取回，不增加 attempt；其他可重試基礎設施失敗採 bounded retry。超過 retry ceiling 的 claim 只做 terminal reconciliation，不能重新呼叫 model、semantic provider 或 verifier。
7. [EP-002] shutdown 先關閉 claim admission，再取消兩個輪詢並在 grace period 內等待；尚未完成的 durable claim 由下一次 startup recovery 接續。

---

## 相關資料與依賴

**讀取的資料來源：**

- Slack Socket Mode `app_mention`
- PostgreSQL source identity、session/inbox、Agent state/event、session history 與 delivery outbox
- Java Semantic Service 與模型 adapter（僅由 inbox worker 在允許的 execution mode 呼叫）

**寫入或影響的資源：**

- PostgreSQL canonical source、payload conflict、inbox、Agent state/event、participant-aware session turns、receipt/final delivery outbox
- Slack channel 訊息 delivery；delivery 成功回報不構成 Slack 端 exactly-once 保證

**依賴的其他業務群組：**

- code-analysis：inbox worker 的 `QUERY` capability 透過 Java Semantic Service 執行唯讀分析
- repo-management：code-analysis 所需 repository lifecycle 與快取由此群組維護

---

## 注意事項

- 啟用 Slack 流程需要 `SPRING_PROFILES_ACTIVE=slack-agent`、`SLACK_APP_TOKEN` 與 `SLACK_BOT_TOKEN`；`SLACK_BOT_USER_ID` 可在啟動時無法透過 Slack `auth.test` 解析時明確提供
- `agent-runtime` 單獨啟用時仍可由 Java inbound contracts 手動驅動，但不建立 Socket Mode 連線或背景工作
- 本流程不宣稱跨 machine/process 的分散式 ownership、lease 或 exactly-once delivery
