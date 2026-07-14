# slack-agent — 業務群組文件

> 所屬專案：java-system-agent
> 最後更新：2026-07-14 21:28
> 來源文件：business-scope.md
> 來源同步：2026-07-14 21:28

---

## 業務概述

Slack AI 助理是本系統的核心互動入口。當使用者在 Slack 中 @mention bot 時，系統先判定問題需要 API 程式碼證據、一般業務程式碼證據或僅需文件，再透過 LLM 工具鏈（DocumentTools + AgentAnalysisTools）取得所需證據，並將受證據閘門約束的結果以自然語言串流回覆至 Slack。
兩個排程任務分別維護去重快取與限流記錄的清理，確保系統穩定運作不會記憶體膨脹。

---

## 進入點

### EP-001
類型：Slack Socket Mode 事件監聽
業務描述：接收 Slack @mention 訊息，經去重與限流後，依問題類型取得文件或程式碼證據，通過確定性的證據閘門後串流回覆
負責業務：Slack 問答、AI 分析、程式碼查詢、業務查詢、agent 對話
findCallGraph：
  packageName: com.java.system.agent.slack.listener
  className: SlackEventListener
  methodSignature: processAppMention
觸發方式：Slack `app_mention` 事件 + @Async
信心：✅
Side Effects：呼叫 SlackEventDeduplicator.isDuplicate（去重）、呼叫 AgentAiService.analyzeWithTools（LLM 分析含 DocumentTools、find_call_graph、find_api_call_graph 與確定性證據閘門）、透過 SlackStreamClient 串流回覆訊息至 Slack channel

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

1. [EP-001] 使用者在 Slack @mention bot，事件經去重檢查（SlackEventDeduplicator）與限流檢查（@RateLimit，每使用者 5 秒冷卻）後，進入 SlackAgentPipeline → AgentAiService.analyzeWithTools。明確 API 問題必須使用 find_api_call_graph；業務流程、規則、條件、計算、判斷與副作用問題可透過 DocumentTools 定位，但必須再使用 find_call_graph 取得程式碼證據；只有文件概覽或使用方式問題可單靠文件完成。確定性的證據閘門決定是否可輸出業務結論，再串流回覆至 Slack
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
- code-analysis：[slack-agent/EP-001] 的 LLM 工具鏈透過 find_api_call_graph 執行 API route 候選定位，或透過 find_call_graph 分析已定位的進入點；兩者的結果都由確定性證據閘門驗證後才可形成業務結論
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

### 程式碼證據政策與強制結束

- 明確 API 問題若未呼叫 find_api_call_graph，證據閘門會要求重試；find_call_graph 不能替代 API route 證據
- 業務行為問題不能只根據 service-map、business-map 或業務群組文件完成，文件僅用於定位，必須取得 call graph 證據
- 找不到 route、多個候選或分析失敗時，回覆分別受限為 NOT_FOUND、AMBIGUOUS 或 ANALYSIS_FAILED 的安全訊息，不得夾帶未驗證的業務結論
- analyst loop 因逾時、turn 上限、取消或錯誤而 forced-finalize 時，終端政策仍會取代缺乏證據的草稿，因此 forced-finalize 不能繞過程式碼證據要求

### Slack 摘要資訊邊界

Slack 的 tool 呼叫摘要會顯示 API method、canonical route、repo 與 evidence status/reason。摘要不顯示 package、class 或 method 名稱；AMBIGUOUS 與 NOT_FOUND 的候選也只包含經清理的 method、route 與 repo。

### 三層錯誤處理機制

- inner LLM（AgentAnalysisTools）失敗 → 返回「（程式碼業務分析暫時無法取得）」，不中斷 outer LLM
- outer LLM 串流錯誤 → `onErrorResume` 發送「❌ 分析發生錯誤: {message}」
- SlackStreamClient 層錯誤 → `stopStream` 時附加「❌ **分析過程中發生錯誤**: {message}」

使用者在 Slack 端都能看到錯誤回饋，不會出現無回應的情況

### tool 呼叫順序

DocumentTools 的導覽順序仍由 LLM 自行決定；但 API_CODE_REQUIRED 必須取得 find_api_call_graph 證據、BUSINESS_CODE_REQUIRED 必須取得 call graph 證據，以及缺乏證據時只能輸出安全回覆，皆由程式碼強制執行

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
