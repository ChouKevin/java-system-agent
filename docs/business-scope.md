# Business Scope

> 專案：java-system-agent
> 文件版本：2026-04-06 21:00
> 技術棧：Spring Boot 3.5.12 + Spring AI 1.1.2 + Spring Modulith + Slack Bolt (Socket Mode) + JGit + JavaParser + springdoc-openapi
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
| ✅ | `com.java.system.agent.api.AnalysisController` | `getApiCallGraph` | POST | `/analysis/api-call-graph` | 透過 API path + HTTP method 跨所有 repo 查詢對應的 call graph（同 path+method 多 repo 時僅回傳最後建立快取的 repo） | 無（唯讀分析）、呼叫 AnalysisService.lookupApi → AnalysisService.analyzeMethod |

---

## Scheduled Jobs

| 信心 | Class | Method | 排程類型 | 排程頻率 | 業務描述 | Side Effects |
|------|-------|--------|---------|----------|----------|--------------|
| ✅ | `com.java.system.agent.ratelimit.RateLimitingService` | `cleanup` | 靜態（`@Scheduled`） | `fixedDelay = 3600000`（每 60 分鐘） | 清理超過 1 小時的限流記錄，防止記憶體膨脹 | 移除 lastRequestMap 中的過期 entry |
| ✅ | `com.java.system.agent.slack.listener.SlackEventDeduplicator` | `cleanup` | 靜態（`@Scheduled`） | `fixedRate = 600000`（每 10 分鐘） | 清理超過 1 小時的 Slack 事件去重快取，防止記憶體膨脹 | 移除 processedEvents 中的過期 entry |

---

## Slack Socket Mode 事件監聽

> 非標準 HTTP/MQ 進入點，透過 Slack Socket Mode 接收事件，為本系統的核心互動入口

| 信心 | Class | Method | 觸發方式 | 業務描述 | Side Effects |
|------|-------|--------|---------|----------|--------------|
| ✅ | `com.java.system.agent.slack.listener.SlackEventListener` | `processAppMention` | Slack `app_mention` 事件 + `@Async` | 接收 Slack @mention 訊息，經去重與限流後，透過 SlackAgentPipeline 呼叫 AgentAiService.analyzeWithTools 進行 LLM 工具鏈分析，串流回覆至 Slack | 呼叫 SlackEventDeduplicator.isDuplicate（去重檢查）、呼叫 AgentAiService.analyzeWithTools（LLM 分析含 DocumentTools + AgentAnalysisTools）、透過 SlackStreamClient 串流回覆訊息至 Slack channel |

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
