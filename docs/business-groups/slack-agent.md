# slack-agent — 業務群組文件

> 所屬專案：java-system-agent
> 最後更新：2026-04-06 21:00
> 來源文件：business-scope.md
> 來源同步：2026-04-06 20:30

---

## 業務概述

Slack AI 助理是本系統的核心互動入口。當使用者在 Slack 中 @mention bot 時，系統接收訊息並透過 LLM 工具鏈（DocumentTools + AgentAnalysisTools）進行靜態分析，將分析結果以自然語言串流回覆至 Slack。
兩個排程任務分別維護去重快取與限流記錄的清理，確保系統穩定運作不會記憶體膨脹。

---

## 進入點

### EP-001
類型：Slack Socket Mode 事件監聽
業務描述：接收 Slack @mention 訊息，經去重與限流後，透過 LLM 工具鏈分析並串流回覆
負責業務：Slack 問答、AI 分析、程式碼查詢、業務查詢、agent 對話
findCallGraph：
  packageName: com.java.system.agent.slack.listener
  className: SlackEventListener
  methodSignature: processAppMention
觸發方式：Slack `app_mention` 事件 + @Async
信心：✅
Side Effects：呼叫 SlackEventDeduplicator.isDuplicate（去重）、呼叫 AgentAiService.analyzeWithTools（LLM 分析含 DocumentTools + AgentAnalysisTools）、透過 SlackStreamClient 串流回覆訊息至 Slack channel

### EP-002
類型：Scheduled Job
業務描述：每 10 分鐘清理超過 1 小時的 Slack 事件去重快取，防止記憶體膨脹
負責業務：去重清理、dedup cleanup、事件去重維護
findCallGraph：
  packageName: com.java.system.agent.slack.listener
  className: SlackEventDeduplicator
  methodSignature: cleanup
排程：fixedRate = 600000（每 10 分鐘）
信心：✅
Side Effects：移除 processedEvents 中的過期 entry

### EP-003
類型：Scheduled Job
業務描述：每 60 分鐘清理超過 1 小時的限流記錄，防止記憶體膨脹
負責業務：限流清理、rate limit cleanup、限流記錄維護
findCallGraph：
  packageName: com.java.system.agent.ratelimit
  className: RateLimitingService
  methodSignature: cleanup
排程：fixedDelay = 3600000（每 60 分鐘）
信心：✅
Side Effects：移除 lastRequestMap 中的過期 entry

---

## 業務流程

1. [EP-001] 使用者在 Slack @mention bot，事件經去重檢查（SlackEventDeduplicator）與限流檢查（@RateLimit，每使用者 5 秒冷卻）後，進入 SlackAgentPipeline → AgentAiService.analyzeWithTools，LLM 透過 DocumentTools 讀取 service-map/business-map 文件定位 repo 與進入點，再透過 AgentAnalysisTools 觸發 call graph 分析，最終串流回覆至 Slack
2. [EP-002] 定時清理去重快取，維護 EP-001 的去重狀態
3. [EP-003] 定時清理限流記錄，維護 EP-001 的限流狀態

---

## 相關資料與依賴

**讀取的資料來源：**
- Slack Socket Mode 事件（app_mention）
- repos/service-map.md、repos/{repoId}/docs/business-map.md、repos/{repoId}/docs/business-groups/*.md（透過 DocumentTools）
- 分析快取（透過 AgentAnalysisTools → AnalysisService）

**寫入或影響的資源：**
- Slack channel（串流回覆訊息）
- 記憶體內去重快取（processedEvents ConcurrentHashMap）
- 記憶體內限流記錄（lastRequestMap ConcurrentHashMap）

**依賴的其他業務群組：**
- code-analysis：[slack-agent/EP-001] 的 LLM 工具鏈內部呼叫 AgentAnalysisTools，最終觸發 AnalysisService.analyzeMethod
- repo-management：分析快取需由 repo-management 群組預先建立

---

## 補充細節

> 以下內容由 QA 驗證過程中深入程式碼後補充，非原始掃描產出

### 限流回覆行為

同一使用者在 5 秒冷卻期內再次 @mention，系統會主動回覆提示訊息：`"<@userId> 請求太頻繁，請稍後再試 (冷卻時間 5 秒)。"`，非靜默丟棄

⚠️ 注意：`processAppMention()` 是 `SlackEventListener` 的同類別內部呼叫。Spring AOP proxy 在 self-invocation 場景下可能不生效，這會導致 `@RateLimit` 和 `@Async` 都不啟動。若出現此問題，應將 `processAppMention` 抽到獨立 bean，或改用 programmatic 呼叫 `rateLimitingService.tryAcquire()`

### 限流 key 維度

限流以 Slack user ID 為 key（`SlackMessageContext.userId` 標記了 `@RateLimitKey`），與頻道無關。同一使用者在不同頻道 @mention bot 共用同一個 5 秒冷卻計時器

### 未 clone repo 的錯誤回饋

使用者查詢尚未 clone 的 repo 時系統不會崩潰，但錯誤訊息籠統（「程式碼業務分析暫時無法取得」），無法區分「repo 未 clone」vs「分析失敗」

### 三層錯誤處理機制

- inner LLM（AgentAnalysisTools）失敗 → 返回「（程式碼業務分析暫時無法取得）」，不中斷 outer LLM
- outer LLM 串流錯誤 → `onErrorResume` 發送「❌ 分析發生錯誤: {message}」
- SlackStreamClient 層錯誤 → `stopStream` 時附加「❌ **分析過程中發生錯誤**: {message}」

使用者在 Slack 端都能看到錯誤回饋，不會出現無回應的情況

### tool 呼叫順序

DocumentTools 和 AgentAnalysisTools 的呼叫順序由 LLM 自行決定。system prompt 建議了 1→2→3→4 的工作流程，但這只是引導，非程式碼強制

---

## QA 驗證紀錄

> 驗證時間：2026-04-06 21:00
> 驗證深度：標準（8 題）

| # | 面向 | 問題 | 結果 | 補強內容 |
| --- | --- | --- | --- | --- |
| Q1 | 正常流程 | 完整流程經過哪些元件？tool 順序？ | ✅ | — |
| Q2 | 正常流程 | fixedRate vs fixedDelay 差異？ | ✅ | — |
| Q3 | 異常處理 | 5 秒內連續兩次 @mention？ | ⚠️→已補強 | 記錄回覆行為與 self-invocation AOP 疑慮 |
| Q4 | 異常處理 | LLM 逾時或錯誤時 Slack 回覆？ | ✅ | — |
| Q5 | 異常處理 | 去重判定邏輯？ | ✅ | — |
| Q6 | 跨群組 | 查詢未 clone 的 repo？ | ⚠️→已補強 | 記錄籠統錯誤訊息問題 |
| Q7 | 跨群組 | entry point 快取未建立時？ | ✅ | — |
| Q8 | 業務規則 | 限流以什麼為單位？ | ✅ | — |

---

## 注意事項

- Slack 連線依賴環境變數 SLACK_APP_TOKEN、SLACK_BOT_TOKEN
- 限流為每使用者 5 秒冷卻（以 Slack user ID 為 key），由 @RateLimit + RateLimitingAspect (AOP) 實現
- LLM 模型由 active profile 決定（dev: OpenAI-compatible, uat/pro: Google Gemini）
- ⚠️ self-invocation 可能導致 @RateLimit/@Async AOP 不生效，需驗證或重構
