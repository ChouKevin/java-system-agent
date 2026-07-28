# Business Scope

> 專案：java-system-agent
> 文件版本：2026-07-28 17:12
> 技術棧：Spring Boot 4 + Spring AI 2.0 + Spring Modulith + Slack Bolt (Socket Mode) + Spring JDBC + PostgreSQL
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

---

## Slack Socket Mode 事件監聽

> 非標準 HTTP/MQ 進入點；僅在 `slack-agent` profile 啟用，並由其 profile group 一併啟用 `agent-runtime`

| 信心 | Class | Method | 觸發方式 | 業務描述 | Side Effects |
|------|-------|--------|----------|----------|--------------|
| ✅ | `com.java.system.agent.slack.source.SlackAppMentionHandler` | `apply` | Slack Socket Mode `app_mention` 回呼 | 只接受支援頻道中的真人 bot mention；正規化來源、參與者與問題後呼叫 durable source admission | 成功持久化後才 ACK；不支援事件安全 ACK |
| ✅ | `com.java.system.agent.inbox.application.SourceAcceptanceApplicationService` | `accept` | 已正規化 Slack 事件 | 以 transport event 與 canonical source message 進行重放去重與 payload 衝突偵測；新事件建立 session、inbox 與立即收據 delivery | PostgreSQL 原子寫入來源、inbox 與 receipt outbox |

---

## 背景工作迴圈

> 不是 `@Scheduled` 工作；`slack-agent` 的 `SmartLifecycle` 在啟動復原完成後啟動兩個固定延遲輪詢迴圈

| 信心 | Class | Method | 觸發方式 | 業務描述 | Side Effects |
|------|-------|--------|----------|----------|--------------|
| ✅ | `com.java.system.agent.worker.AgentWorkerManager` | `start` | `SmartLifecycle` auto-start | 先將 interrupted inbox/delivery claims 復原，再啟動一個 inbox 與一個 delivery 輪詢迴圈 | 每次最多處理一筆；同一 process 只允許一筆 inbox claim，並維持同 session 順序 |
| ✅ | `com.java.system.agent.worker.AgentWorkerManager` | `stop` | `SmartLifecycle` graceful stop | 停止新的 claim、取消輪詢並等待設定的 grace period | 已持久化工作留待下次啟動復原 |

---

## 啟動初始化

> 應用程式啟動時執行一次的初始化邏輯

| 信心 | Class | Method | 觸發方式 | 業務描述 | Side Effects |
|------|-------|--------|----------|----------|--------------|
| ✅ | `com.java.system.agent.git.service.CacheWarmerService` | `warmupCache` | `@PostConstruct` | 啟動時預載所有已設定 repository 的 metadata 快取 | 呼叫 RepoRegistryPort.all → AnalysisService.reloadRepo |
| ✅ | `com.java.system.agent.common.config.StartupConfigLogger` | `run` | `CommandLineRunner` | 啟動時記錄應用程式設定資訊至 log | 寫入 log |

---

## Message Queue Consumers

> 本專案未使用任何訊息佇列消費者

（無）

---

## 未啟用 / 已停用的進入點

> 未發現任何未啟用或已停用的進入點

（無）
