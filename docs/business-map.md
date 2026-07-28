# Business Map

> 專案：java-system-agent
> 更新時間：2026-07-28 17:12
> 來源：business-scope.md
> 來源同步：2026-07-28 17:12

---

## 業務群組

### repo-management

說明：Git repository 的生命週期管理（clone、pull、checkout、狀態查詢）與啟動快取預載
關鍵字：repo、repository、clone、pull、checkout、branch、git、快取預載、warmup、reloadRepo、快取重載、invalidate、前置條件、eventually consistent
業務群組文件：business-groups/repo-management.md

### code-analysis

說明：Java 程式碼靜態分析，透過指定方法或 API path 產生 call graph
關鍵字：call graph、呼叫鏈、靜態分析、analysis、flatten、API 查詢、api-call-graph、trie、analyzeMethod、lookupApi、TRAVERSAL_CUTOFF、深度截斷、路徑變數、wildcard、介面解析
業務群組文件：business-groups/code-analysis.md

### slack-agent

說明：Slack AI 助理以 Socket Mode 接收支援的 @mention，先完成 PostgreSQL durable admission 與收據 outbox，再依序執行 Agent 與最終回覆 delivery
關鍵字：Slack、Socket Mode、@mention、durable admission、canonical source、payload conflict、inbox、receipt、final delivery、participant、session ordering、model capacity、retry、terminal reconciliation、recovery、graceful shutdown
業務群組文件：business-groups/slack-agent.md

### misc

說明：未分類進入點（啟動設定 log）
關鍵字：（無）
業務群組文件：business-groups/misc.md
