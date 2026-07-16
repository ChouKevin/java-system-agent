# Business Map

> 專案：java-system-agent
> 更新時間：2026-07-15 18:15
> 來源：business-scope.md
> 來源同步：2026-07-15 18:15

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

說明：Slack AI 助理，接收 @mention 後透過 LLM 工具鏈分析程式碼並串流回覆，含去重、限流、決策追蹤（UAT 預設持久化）與 dev/uat 診斷查詢
關鍵字：Slack、AI、agent、@mention、LLM、tool、問答、串流回覆、traceId、threadTs、決策追蹤、debug trace、去重、dedup、限流、rate limit、cleanup、user ID、冷卻、錯誤回饋、async
業務群組文件：business-groups/slack-agent.md

### misc

說明：未分類進入點（啟動設定 log）
關鍵字：（無）
業務群組文件：business-groups/misc.md
