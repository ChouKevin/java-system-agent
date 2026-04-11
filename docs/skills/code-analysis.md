# code-analysis — 業務 Skill

> 所屬專案：java-system-agent
> 最後更新：2026-04-06 21:00
> 來源文件：business-scope.md
> 來源同步：2026-04-06 20:30

---

## 業務概述

程式碼靜態分析業務提供 Java 程式碼的 call graph 分析能力。
使用者可透過指定 package/class/method 直接查詢，也可透過 API path + HTTP method 反查對應的進入點再產生 call graph。
分析結果以扁平化結構回傳，涵蓋從進入點往下的完整呼叫鏈。

---

## 進入點

### EP-001
類型：REST API
業務描述：根據指定的 package/class/method 產生 call graph
負責業務：call graph 分析、方法呼叫鏈、靜態分析
findCallGraph：
  packageName: com.java.system.agent.api
  className: CallGraphController
  methodSignature: getCallGraph
HTTP：POST /analysis/call-graph/{repo}
信心：✅
Side Effects：無（唯讀分析）、呼叫 AnalysisService.analyzeMethod

### EP-002
類型：REST API
業務描述：根據指定的 package/class/method 產生扁平化 call graph
負責業務：扁平化 call graph、flatten、方法呼叫鏈
findCallGraph：
  packageName: com.java.system.agent.api
  className: CallGraphController
  methodSignature: getCallGraphFlatten
HTTP：POST /analysis/call-graph/{repo}/flatten
信心：✅
Side Effects：無（唯讀分析）、呼叫 AnalysisService.analyzeMethod

### EP-003
類型：REST API
業務描述：透過 API path + HTTP method 跨所有 repo 查詢對應的 call graph
負責業務：API 路徑查詢、反查 call graph、API lookup、trie 查詢
findCallGraph：
  packageName: com.java.system.agent.api
  className: AnalysisController
  methodSignature: getApiCallGraph
HTTP：POST /analysis/api-call-graph
信心：✅
Side Effects：無（唯讀分析）、呼叫 AnalysisService.lookupApi → AnalysisService.analyzeMethod

---

## 業務流程

1. [EP-001] / [EP-002] 使用者已知目標方法，直接指定 package/class/method 取得 call graph
2. [EP-003] 使用者只知道 API path（如 `/api/v1/orders`），透過 trie 索引反查到對應的 class/method，再產生 call graph

---

## 相關資料與依賴

**讀取的資料來源：**
- 分析快取（parser、entryPoint、classMetadata、trie）— 由 repo-management 群組的 reloadRepo 建立

**寫入或影響的資源：**
- 無（純唯讀分析）

**依賴的其他業務群組：**
- repo-management：分析快取由 [repo-management/EP-003] pullRepo 或 [repo-management/EP-006] warmupCache 建立，若快取未就緒則無法分析

---

## 補充細節

> 以下內容由 QA 驗證過程中深入程式碼後補充，非原始掃描產出

### EP-001 與 EP-002 重複問題

兩個端點目前程式碼完全相同，都呼叫 `AnalysisService.analyzeMethod()` 回傳 `FlattenedCallGraph`。從命名推測原始意圖可能是 EP-001 回傳樹狀結構、EP-002 回傳扁平化結構，但實作中 `analyzeMethod` 內部統一呼叫 `analyzeFlattened()`

### 多 repo 同 API path 覆蓋問題

Trie 的每個葉節點 `methodMap` 是 `Map<String, ApiEntryPointRef>`，同一個 HTTP method 只能存一個 ref。若多個 repo 有相同 API path + HTTP method，後建立快取的 repo 會覆蓋前者。EP-003 只回傳一個結果

### 深度截斷行為

當遞迴深度 == `maxDepth`（目前為 3）時，節點標記為 `TRAVERSAL_CUTOFF`，但仍展開一層 children 作為 callees（只有 signature，不建立獨立 entry）。深度 > `maxDepth` 的節點不進入 call graph。flattened 輸出中，TRAVERSAL_CUTOFF 節點帶有完整原始碼與 callees 列表，供 LLM 判斷是否需要透過 `find_call_graph` 工具進一步展開

### trie 路徑變數匹配規則

插入時所有 `{xxx}` 路徑段正規化為 `{*}`。查詢時 exact match 優先，找不到再 fallback 到 `{*}` wildcard 節點。不要求變數名稱一致

### 介面方法作為入口的限制

若入口方法指向介面的 abstract method（無方法體），call graph 只有根節點，不會展開到實作類別。只有在遞迴過程中遇到介面時（被呼叫端是介面），才會透過 `findImplementationsByName` 展開所有實作

### 快取未建立時的錯誤行為

- repo 未在 application.yml 設定 → `UnknownRepoException` → HTTP 500
- repo 已設定但未 clone → parser 解析失敗 → HTTP 200 空的 call graph（不一致的錯誤回應）

---

## QA 驗證紀錄

> 驗證時間：2026-04-06 21:00
> 驗證深度：標準（8 題）

| # | 面向 | 問題 | 結果 | 補強內容 |
| --- | --- | --- | --- | --- |
| Q1 | 正常流程 | EP-001 與 EP-002 有何區分？ | ⚠️→已補強 | 記錄兩端點目前實作相同 |
| Q2 | 正常流程 | 多 repo 匹配同一 API path？ | ⚠️→已補強 | 記錄 trie 覆蓋行為 |
| Q3 | 異常處理 | 快取未建立時 EP-001 回傳？ | ❌→已補強 | 記錄不一致的錯誤回應 |
| Q4 | 異常處理 | call graph 深度超過 3 層？ | ✅ | — |
| Q5 | 異常處理 | EP-003 查詢不存在的 API path？ | ✅ | — |
| Q6 | 跨群組 | pullRepo 後快取是否自動重建？ | ⚠️→已補強 | 與 repo-management 交叉引用 |
| Q7 | 業務規則 | trie 路徑變數匹配規則？ | ✅ | — |
| Q8 | 業務規則 | 入口為介面 abstract method？ | ⚠️→已補強 | 記錄已知限制 |

---

## 注意事項

- call graph 深度由 `entry-point.call-graph-depth` 設定控制（目前為 3）
- EP-001 與 EP-002 目前呼叫相同的 AnalysisService.analyzeMethod，回傳結構相同
- ⚠️ 多 repo 相同 API path 會互相覆蓋，只保留最後建立快取的 repo
- ⚠️ 入口方法為介面 abstract method 時不展開實作，回傳近乎空的 call graph
