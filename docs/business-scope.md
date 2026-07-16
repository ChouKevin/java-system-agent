# Business Scope

> 專案：java-system-agent
> 文件版本：2026-07-15 18:15
> 技術棧：Spring Boot 3.5.16 + Spring AI 1.1.8 + Spring Modulith + Slack Bolt (Socket Mode) + Spring JDBC + PostgreSQL + JGit + JavaParser + springdoc-openapi
> Active Profile：dev

---

## REST API

| 信心 | Class | Method | HTTP Method | URL | 業務描述 | Side Effects |
|------|-------|--------|-------------|-----|----------|--------------|
| ✅ | `com.java.system.agent.api.RepoController` | `listRepos` | GET | `/git/repos` | 列出所有已設定的 managed repository | 無 |
| ✅ | `com.java.system.agent.api.RepoController` | `cloneRepo` | POST | `/git/clone-repo/{repo}` | 從遠端 clone 指定 repository 到本地 repos 目錄，branch 可選（clone 後不自動載入分析快取，需另呼叫 pull-repo 觸發） | 寫入本地檔案系統（repos/ 目錄）、呼叫 GitService.cloneRepository |
| ✅ | `com.java.system.agent.api.RepoController` | `pullRepo` | POST | `/git/pull-repo/{repo}` | 拉取指定 repository 的最新變更，並重新載入分析快取 | 更新本地檔案系統、呼叫 GitService.pullRepository、呼叫 AnalysisService.reloadRepo 重建 parser/entryPoint/classMetadata/trie 快取 |
| ✅ | `com.java.system.agent.api.RepoController` | `checkoutRepo` | POST | `/git/checkout-repo/{repo}` | 切換指定 repository 到特定 branch 或 commit（不觸發快取重載，需另呼叫 pull-repo 刷新） | 更新本地檔案系統、呼叫 GitService.checkoutBranch |
| ✅ | `com.java.system.agent.api.RepoController` | `currentBranch` | GET | `/git/current-branch/{repo}` | 查詢指定 repository 目前 checkout 的 branch 名稱 | 無 |
| ✅ | `com.java.system.agent.api.CallGraphController` | `getCallGraph` | POST | `/analysis/call-graph/{repo}` | 根據指定的 package/class/method 產生 call graph | 無（唯讀分析）、呼叫 AnalysisService.analyzeMethod |
| ✅ | `com.java.system.agent.api.CallGraphController` | `getCallGraphFlatten` | POST | `/analysis/call-graph/{repo}/flatten` | 根據指定的 package/class/method 產生扁平化 call graph（目前與 EP-001 實作相同） | 無（唯讀分析）、呼叫 AnalysisService.analyzeMethod |
| ✅ | `com.java.system.agent.api.AnalysisController` | `getApiCallGraph` | POST | `/analysis/api-call-graph` | 透過 API path + HTTP method 跨所有 repo 查詢對應的 call graph（public endpoint 維持單一結果；Slack 內部工具另以候選查詢避免跨 repo 碰撞時靜默選擇） | 無（唯讀分析）、呼叫 AnalysisService.lookupApi → AnalysisService.analyzeMethod |
| ✅ | `com.java.system.agent.ai.controller.TraceDebugController` | `search` | GET | `/debug/trace` | 在 dev/uat 依使用者、Slack 事件、接受狀態與時間範圍查詢 agent trace 摘要，用於追查 LLM 決策結果 | 唯讀查詢 agent trace 儲存 |
| ✅ | `com.java.system.agent.ai.controller.TraceDebugController` | `conversation` | GET | `/debug/trace/conversations/{threadTs}` | 在 dev/uat 依 Slack threadTs 以時間正序查詢同一 thread 中已進入 AI 分析且成功保存的問答與決策追蹤 | 唯讀查詢 agent trace 儲存 |
| ✅ | `com.java.system.agent.ai.controller.TraceDebugController` | `recent` | GET | `/debug/trace/{conversationId}` | 在 dev/uat 以舊版 conversationId 相容路徑，依時間倒序查詢該對話保留的全部 agent trace | 唯讀查詢 agent trace 儲存 |
| ✅ | `com.java.system.agent.ai.controller.TraceDebugController` | `byTraceId` | GET | `/debug/trace/id/{traceId}` | 在 dev/uat 依單一 traceId 查詢問題、LLM turn、tool 觀測、驗證閘門與終止原因等詳細追蹤資訊 | 唯讀查詢 agent trace 儲存 |

---

## Scheduled Jobs

| 信心 | Class | Method | 排程類型 | 排程頻率 | 業務描述 | Side Effects |
|------|-------|--------|---------|----------|----------|--------------|
| ✅ | `com.java.system.agent.ratelimit.RateLimitingService` | `cleanup` | 靜態（`@Scheduled`） | `fixedDelay = 3600000`（每 60 分鐘） | 清理超過 1 小時的限流記錄，防止記憶體膨脹 | 移除 lastRequestMap 中的過期 entry |
| ✅ | `com.java.system.agent.slack.listener.SlackEventDeduplicator` | `cleanup` | 靜態（`@Scheduled`） | `fixedRate = 600000`（每 10 分鐘） | 清理超過 1 小時的 Slack 事件去重快取，防止記憶體膨脹 | 移除 processedEvents 中的過期 entry |
| ✅ | `com.java.system.agent.ai.trace.TracePartitionManager` | `initializePartitions` | 靜態（`@Scheduled`） | `cron = "0 5 0 * * *"`, UTC（每日 00:05 UTC） | 在啟用 trace 持久化時確保本月與下月可接收 agent trace，並在應用啟動時先執行一次 | 建立或確認當前與下一月的 trace 儲存分區 |

---

## Slack Socket Mode 事件監聽

> 非標準 HTTP/MQ 進入點，透過 Slack Socket Mode 接收事件，為本系統的核心互動入口

| 信心 | Class | Method | 觸發方式 | 業務描述 | Side Effects |
|------|-------|--------|---------|----------|--------------|
| ✅ | `com.java.system.agent.slack.listener.SlackEventListener` | `buildContext` | Slack `app_mention` 回呼 | 從 Slack 事件建立包含全新 traceId、userId、eventId 與 threadTs 的請求上下文；新對話以事件 ts 作為 threadTs | 無（僅建立請求上下文） |
| ✅ | `com.java.system.agent.slack.listener.SlackEventListener` | `handleAppMention` | `buildContext` 後的同步事件處理 | 先以 Slack user ID 檢查限流，再以 eventId 取得去重處理權；限流或重複事件不進入 AI 分析，也不保存 agent trace | 限流時回覆 Slack 冷卻訊息；成功時呼叫 `processAppMention` |
| ✅ | `com.java.system.agent.slack.listener.SlackEventListener` | `processAppMention` | `handleAppMention` 通過後 + `@Async` | 啟動 SlackAgentPipeline 與 AgentAiService 分析。通過驗證的 token 可在 Done 前串流到 Slack，Done 時寫入對話記憶並保存 trace；被拒絕時先保存 trace，成功後才回覆含 traceId 的安全訊息 | 呼叫 AgentAiService.analyzeWithTools、嘗試保存 agent trace、透過 SlackStreamClient 串流回覆；拒絕 trace 保存失敗時回覆不含 traceId 的固定安全訊息 |

> Trace 儲存預設：UAT 啟用 PostgreSQL 持久化；dev 與 pro 使用有界的記憶體儲存。

---

## 啟動初始化

> 應用程式啟動時執行一次的初始化邏輯

| 信心 | Class | Method | 觸發方式 | 業務描述 | Side Effects |
|------|-------|--------|---------|----------|--------------|
| ✅ | `com.java.system.agent.git.service.CacheWarmerService` | `warmupCache` | `@PostConstruct` | 啟動時預載所有已設定 repository 的 metadata 快取（parser、entryPoint、classMetadata、trie） | 呼叫 RepoRegistryPort.all → AnalysisService.reloadRepo 逐一建立快取 |
| ✅ | `com.java.system.agent.common.config.StartupConfigLogger` | `run` | `CommandLineRunner` | 啟動時記錄應用程式設定資訊（active profile、datasource 等）至 log | 寫入 log |

---

## Message Queue Consumers

> 本專案未使用任何訊息佇列消費者

（無）

---

## 未啟用 / 已停用的進入點

> 未發現任何未啟用或已停用的進入點

（無）
