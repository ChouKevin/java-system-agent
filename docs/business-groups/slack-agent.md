# slack-agent — 業務群組文件

> 所屬專案：java-system-agent
> 最後更新：2026-07-15 18:15
> 來源文件：business-scope.md
> 來源同步：2026-07-15 18:15

---

## 業務概述

Slack AI 助理是本系統的核心互動入口。Slack `app_mention` 回呼先由 `buildContext` 為當次事件建立 traceId 與 threadTs，`handleAppMention` 再執行限流與去重，通過後由 `processAppMention` 非同步啟動 LLM 工具鏈。通過驗證的 token 可在分析 Done 前送到 Slack，Done 時才寫入 chat memory 與保存 trace；被拒絕的草稿先嘗試保存 trace，保存成功才回覆含 traceId 的安全訊息。限流與重複事件不進入 AI 分析，因此不保存 trace。
三個排程任務分別維護去重快取、限流記錄與 trace 儲存分區；dev/uat 提供摘要、已成功保存的 thread 追蹤與單筆 trace 診斷查詢。UAT 預設使用 PostgreSQL 持久化，dev 與 pro 預設使用有界的記憶體儲存。

---

## 進入點

### EP-001
類型：Slack Socket Mode 事件監聽
業務描述：`buildContext` 建立 traceId 與 Slack 關聯資訊，`handleAppMention` 依序執行限流與去重，通過後 `processAppMention` 才非同步啟動 AI 分析與 Slack 串流
負責業務：Slack 問答、AI 分析、程式碼查詢、業務查詢、agent 對話
findCallGraph：
  packageName: com.java.system.agent.slack.listener
  className: SlackEventListener
  methodSignature: start
觸發方式：`start` 註冊 Slack `app_mention` 回呼；回呼依序執行 `buildContext`、`handleAppMention`，通過同步閘門後由 `processAppMention` 的 `@Async` 執行分析
信心：✅
Side Effects：限流時回覆冷卻訊息；通過同步閘門後呼叫 AgentAiService.analyzeWithTools、嘗試保存 agent trace、透過 SlackStreamClient 回覆 Slack；重複或限流事件不寫入 trace

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

### EP-004
類型：Scheduled Job
業務描述：在啟用 trace 持久化時維護本月與下月的儲存分區，並在應用啟動時先執行一次
負責業務：trace 儲存維護、追蹤持久化可用性
findCallGraph：
  packageName: com.java.system.agent.ai.trace
  className: TracePartitionManager
  methodSignature: initializePartitions
排程：`cron = "0 5 0 * * *"`, UTC（每日 00:05 UTC）+ `@PostConstruct`
信心：✅
Side Effects：建立或確認當前與下一月的 trace 儲存分區

### EP-005
類型：REST API（dev/uat 診斷端點）
業務描述：依使用者、Slack 事件、接受狀態與時間範圍查詢 agent trace 摘要
負責業務：LLM 決策追蹤、trace 查詢
findCallGraph：
  packageName: com.java.system.agent.ai.controller
  className: TraceDebugController
  methodSignature: search
觸發方式：`GET /debug/trace`
信心：✅
Side Effects：無（唯讀查詢 agent trace 儲存）

### EP-006
類型：REST API（dev/uat 診斷端點）
業務描述：依 Slack threadTs 以時間正序查詢同一 thread 中已進入 AI 分析且成功保存的問答與決策追蹤
負責業務：Slack thread 追蹤、已儲存對話流程查詢
findCallGraph：
  packageName: com.java.system.agent.ai.controller
  className: TraceDebugController
  methodSignature: conversation
觸發方式：`GET /debug/trace/conversations/{threadTs}`
信心：✅
Side Effects：無（唯讀查詢 agent trace 儲存）

### EP-007
類型：REST API（dev/uat 診斷端點）
業務描述：以舊版 conversationId 相容路徑，依時間倒序查詢該對話保留的全部 agent trace
負責業務：trace 相容查詢
findCallGraph：
  packageName: com.java.system.agent.ai.controller
  className: TraceDebugController
  methodSignature: recent
觸發方式：`GET /debug/trace/{conversationId}`
信心：✅
Side Effects：無（唯讀查詢 agent trace 儲存）

### EP-008
類型：REST API（dev/uat 診斷端點）
業務描述：依單一 traceId 查詢問題、LLM turn、tool 觀測、驗證閘門與終止原因等詳細追蹤資訊
負責業務：單筆 LLM 決策追蹤、trace 細節查詢
findCallGraph：
  packageName: com.java.system.agent.ai.controller
  className: TraceDebugController
  methodSignature: byTraceId
觸發方式：`GET /debug/trace/id/{traceId}`
信心：✅
Side Effects：無（唯讀查詢 agent trace 儲存）

---

## 業務流程

1. [EP-001] Slack `app_mention` 回呼執行 `buildContext`，為事件建立新 traceId 並選定 threadTs；`handleAppMention` 先以 Slack user ID 執行 5 秒冷卻限流，再以 eventId 取得去重處理權。限流或重複事件在這裡終止，不會進入 AI 分析或寫入 trace。
2. [EP-001] 通過同步閘門後，`processAppMention` 非同步進入 SlackAgentPipeline → AgentAiService.analyzeWithTools，並讀取同一 threadTs 的對話記憶。明確 API 問題必須使用 find_api_call_graph，業務行為問題必須取得 call graph 證據。
3. [EP-001] 當結果通過驗證時，已通過輸出規則的 token 可在 Done 事件前串流到 Slack；Done 時才將 user/assistant 訊息寫回 chat memory，並嘗試保存當次 trace。
4. [EP-001] 當結果被拒絕時，草稿不寫入 chat memory 也不送到 Slack；Done 先嘗試保存草稿與決策原因，成功後回覆含 traceId 的安全訊息，失敗時回覆不含 traceId 的固定安全訊息。
5. [EP-002] 定時清理去重快取，維護 EP-001 的去重狀態。
6. [EP-003] 定時清理限流記錄，維護 EP-001 的限流狀態。
7. [EP-004] 應用啟動時與每日定時維護 trace 儲存分區，使 UAT 成功保存的 trace 在應用重啟或重新部署後仍可查詢。
8. [EP-005] 在 dev/uat 以篩選條件查詢成功保存的 trace 摘要，快速定位使用者、事件、接受狀態或時間範圍。
9. [EP-006] 在 dev/uat 以 threadTs 正序查詢已進入 AI 分析且成功保存的 trace，不包含限流或重複事件。
10. [EP-007] 在 dev/uat 透過舊版 conversationId 路徑，以最新優先順序查詢該對話保留的全部 trace。
11. [EP-008] 在 dev/uat 以 traceId 查詢單次已成功保存請求的完整詳細資訊。

---

## 相關資料與依賴

**讀取的資料來源：**
- Slack Socket Mode 事件（app_mention）
- knowledge/service-map.md、knowledge/repos/{repoId}/business-map.md、knowledge/repos/{repoId}/business-groups/{groupName}.md（透過 DocumentTools）
- 分析快取（透過 AgentAnalysisTools → AnalysisService）
- 同一 Slack threadTs 的對話記憶與 agent trace 儲存（UAT 預設持久化，dev/pro 預設保存在有界記憶體）

**寫入或影響的資源：**
- Slack channel（串流回覆訊息）
- agent trace 儲存（問題、對話快照、LLM 觀測、閘門決策、終止原因與 Slack 實際回覆；UAT 預設持久化）
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

`handleAppMention()` 在同步路徑以 `rateLimitingService.tryAcquire()` 執行限流，並透過自身 Spring proxy 呼叫 `processAppMention()`，使 `@Async` 能在代理邊界生效。

### 限流 key 維度

限流以 Slack user ID 為 key（`SlackMessageContext.userId` 標記了 `@RateLimitKey`），與頻道無關。同一使用者在不同頻道 @mention bot 共用同一個 5 秒冷卻計時器

### 未 clone repo 的錯誤回饋

使用者查詢尚未 clone 的 repo 時系統不會崩潰，但錯誤訊息籠統（「程式碼業務分析暫時無法取得」），無法區分「repo 未 clone」vs「分析失敗」

### 程式碼證據政策與強制結束

- 明確 API 問題若未呼叫 find_api_call_graph，證據閘門會要求重試；find_call_graph 不能替代 API route 證據
- 業務行為問題不能只根據 service-map、business-map 或業務群組文件完成，文件僅用於定位，必須取得 call graph 證據
- 找不到 route、多個候選或分析失敗時，回覆分別受限為 NOT_FOUND、AMBIGUOUS 或 ANALYSIS_FAILED 的安全訊息，不得夾帶未驗證的業務結論
- analyst loop 因逾時、turn 上限、取消或錯誤而 forced-finalize 時，終端政策仍會取代缺乏證據的草稿，因此 forced-finalize 不能繞過程式碼證據要求

### 被拒絕答案與追蹤行為

- `accepted=false` 時，LLM 草稿不會送到 Slack，也不會寫入 chat memory
- 系統會先保存完整 trace，包含草稿、各 turn 決策、tool 觀測與拒絕原因，再回覆含 traceId 的安全訊息
- 若 trace 儲存失敗，Slack 仍會取得不含草稿與內部細節的固定安全訊息，但不會顯示無法查詢的 traceId
- 同一 Slack threadTs 中，每次進入 AI 分析且成功保存的請求使用不同 traceId，可透過 thread 查詢串聯已保存的對話流程；限流、重複或保存失敗的請求不在其中

### Slack 摘要資訊邊界

Slack 的 tool 呼叫摘要會顯示經 allowlist 清理的 API method、path、repo 與 evidence status/reason；完整 URL 只保留 path，不顯示 origin、query 或 fragment。摘要不顯示 package、class 或 method 名稱；AMBIGUOUS 與 NOT_FOUND 的候選也只包含經清理的 method、route 與 repo。

### 三層錯誤處理機制

- inner LLM（AgentAnalysisTools）失敗 → 返回「（程式碼業務分析暫時無法取得）」，不中斷 outer LLM
- outer LLM 串流錯誤 → 需要 code evidence 時依當前 evidence 狀態回覆安全 fallback；DOCS_ONLY 回覆固定通用訊息；逾時維持固定逾時訊息。例外與 stack trace 只寫入內部 log，不回傳給 Slack
- SlackStreamClient 層錯誤 → `stopStream` 時附加固定的安全錯誤訊息，內部例外細節只留在 log

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
| Q3 | 異常處理 | 5 秒內連續兩次 @mention？ | ⚠️→已補強 | 記錄回覆行為與同步限流路徑 |
| Q4 | 異常處理 | LLM 逾時或錯誤時 Slack 回覆？ | ✅ | — |
| Q5 | 異常處理 | 去重判定邏輯？ | ✅ | — |
| Q6 | 跨群組 | 查詢未 clone 的 repo？ | ⚠️→已補強 | 記錄籠統錯誤訊息問題 |
| Q7 | 跨群組 | entry point 快取未建立時？ | ✅ | — |
| Q8 | 業務規則 | 限流以什麼為單位？ | ✅ | — |

---

## 注意事項

- Slack 連線依賴環境變數 SLACK_APP_TOKEN、SLACK_BOT_TOKEN
- 限流為每使用者 5 秒冷卻（以 Slack user ID 為 key），由 SlackEventListener 同步呼叫 RateLimitingService.tryAcquire 實現
- LLM 模型由 active profile 決定（dev: OpenAI-compatible, uat/pro: Google Gemini）
- Debug Trace API 只在 dev/uat profile 啟用；UAT 啟用 trace 持久化後，應用重啟或重新部署不會清除已保存追蹤
