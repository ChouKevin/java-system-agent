# repo-management — 業務群組文件

> 所屬專案：java-system-agent
> 最後更新：2026-04-06 21:00
> 來源文件：business-scope.md
> 來源同步：2026-04-06 20:30

---

## 業務概述

Repository 管理業務負責所有被管理的 Git repository 的生命週期操作，包含 clone、pull、branch 切換與狀態查詢。
pull 操作後會自動重新載入分析快取（parser、entryPoint、classMetadata、trie），確保後續靜態分析使用最新程式碼。
應用程式啟動時，CacheWarmerService 會自動對所有已設定的 repo 預載快取，為分析模組提供即時可用的資料。

---

## 進入點

### EP-001
類型：REST API
業務描述：列出所有已設定的 managed repository
負責業務：repo 清單、repository 列表、查詢 repo
findCallGraph：
  packageName: com.java.system.agent.api
  className: RepoController
  methodSignature: listRepos
HTTP：GET /git/repos
信心：✅
Side Effects：無

### EP-002
類型：REST API
業務描述：從遠端 clone 指定 repository 到本地 repos 目錄，branch 可選（clone 後不自動載入分析快取）
負責業務：clone repo、下載 repo、初始化 repo
findCallGraph：
  packageName: com.java.system.agent.api
  className: RepoController
  methodSignature: cloneRepo
HTTP：POST /git/clone-repo/{repo}
信心：✅
Side Effects：寫入本地檔案系統（repos/ 目錄）、呼叫 GitService.cloneRepository

### EP-003
類型：REST API
業務描述：拉取指定 repository 的最新變更，並重新載入分析快取
負責業務：pull repo、更新 repo、同步 repo、重載快取
findCallGraph：
  packageName: com.java.system.agent.api
  className: RepoController
  methodSignature: pullRepo
HTTP：POST /git/pull-repo/{repo}
信心：✅
Side Effects：更新本地檔案系統、呼叫 GitService.pullRepository、呼叫 AnalysisService.reloadRepo 重建 parser/entryPoint/classMetadata/trie 快取

### EP-004
類型：REST API
業務描述：切換指定 repository 到特定 branch 或 commit（不觸發快取重載，需另呼叫 pull-repo 刷新）
負責業務：checkout branch、切換分支、切換版本
findCallGraph：
  packageName: com.java.system.agent.api
  className: RepoController
  methodSignature: checkoutRepo
HTTP：POST /git/checkout-repo/{repo}
信心：✅
Side Effects：更新本地檔案系統、呼叫 GitService.checkoutBranch

### EP-005
類型：REST API
業務描述：查詢指定 repository 目前 checkout 的 branch 名稱
負責業務：查詢 branch、目前分支、current branch
findCallGraph：
  packageName: com.java.system.agent.api
  className: RepoController
  methodSignature: currentBranch
HTTP：GET /git/current-branch/{repo}
信心：✅
Side Effects：無

### EP-006
類型：啟動初始化
業務描述：啟動時預載所有已設定 repository 的 metadata 快取
負責業務：快取預載、warmup、啟動初始化
findCallGraph：
  packageName: com.java.system.agent.git.service
  className: CacheWarmerService
  methodSignature: warmupCache
觸發方式：@PostConstruct（應用程式啟動時執行一次）
信心：✅
Side Effects：呼叫 RepoRegistryPort.all → AnalysisService.reloadRepo 逐一建立快取

---

## 業務流程

1. [EP-002] 透過 API clone 新的 repository 到本地
2. [EP-006] 應用程式啟動時自動對所有已設定的 repo 預載分析快取
3. [EP-003] 需要更新時 pull 最新程式碼並重載快取
4. [EP-004] 需要分析特定版本時 checkout 到指定 branch
5. [EP-001] / [EP-005] 查詢 repo 清單或目前 branch 狀態

---

## 相關資料與依賴

**讀取的資料來源：**
- application.yml `git.repos` 設定（repo URL、default-branch）
- 本地 repos/ 目錄的 Git repository

**寫入或影響的資源：**
- 本地檔案系統（repos/ 目錄）
- 分析快取（parser、entryPoint、classMetadata、trie）

**依賴的其他業務群組：**
- code-analysis：[repo-management/EP-003] pull 後觸發 reloadRepo，影響 code-analysis 群組的分析結果

---

## 補充細節

> 以下內容由 QA 驗證過程中深入程式碼後補充，非原始掃描產出

### clone 後的快取載入

clone-repo 只執行 `GitService.cloneRepository()`，**不會**呼叫 `AnalysisService.reloadRepo()`。因此 clone 完成後分析快取尚未建立，需要：

- 呼叫 `POST /git/pull-repo/{repo}` 觸發 reloadRepo，或
- 重啟應用程式由 CacheWarmerService 自動預載

目前沒有專用 API 可查詢快取狀態，只能透過 log 觀察或直接呼叫分析 API 確認

### CacheWarmerService 預載範圍

只載入每個 repo 在本地 working directory **當前所在 branch** 的快取，不會載入其他曾 checkout 過的 branch。若 repo 尚未 clone（目錄不存在），會 log warn 並跳過，不中斷啟動

### checkout 不觸發快取重載

`checkoutRepo` 只呼叫 `GitService.checkoutBranch()`，**不會**呼叫 `AnalysisService.reloadRepo()`。切換 branch 後若不手動呼叫 pull-repo，靜態分析會拿到**舊 branch 的快取結果**

### 操作前置條件

- pull-repo 和 checkout-repo 需要 repo 已被 clone，否則 JGit 拋出 RuntimeException（不會自動 fallback 到 clone）
- clone-repo 不帶 branch 參數時使用 `default-branch` 設定值。若該 branch 在 remote 不存在，clone 直接失敗不 fallback。若 `default-branch` 未設定，JGit 使用 remote HEAD

### reloadRepo 失敗處理

reloadRepo 依序執行四步驟（invalidate parser → reload entryPoint → reload classMetadata → reload trie），非事務性。中間步驟失敗會留在部分更新的不一致狀態。恢復方式：重新呼叫 pull-repo 觸發完整 reload，或重啟應用

### 並行安全模型

reload 與 call graph 分析之間無全域讀寫鎖。各快取透過 ConcurrentHashMap + immutable snapshot 保證不會讀到損壞資料。reload 期間進行中的分析可能使用舊版快取，屬於 eventually consistent

### URL 為空的 repo

application.yml 中 repo URL 環境變數為空值時，啟動預載會安全跳過（快取為空）。後續嘗試 clone 該 repo 時會拋出 `IllegalStateException("No URL configured for repo: ...")`

---

## QA 驗證紀錄

> 驗證時間：2026-04-06 21:00
> 驗證深度：標準（8 題）

| # | 面向 | 問題 | 結果 | 補強內容 |
| --- | --- | --- | --- | --- |
| Q1 | 正常流程 | 全新 repo 從設定到可分析的完整操作順序？ | ⚠️→已補強 | 補充 clone 後快取載入說明 |
| Q2 | 正常流程 | CacheWarmerService 預載的 branch 範圍？ | ⚠️→已補強 | 明確說明只載入當前 checkout branch |
| Q3 | 異常處理 | 未 clone 直接呼叫 pull/checkout？ | ❌→已補強 | 新增操作前置條件段落 |
| Q4 | 異常處理 | reloadRepo 部分階段失敗？ | ❌→已補強 | 新增 reloadRepo 失敗處理段落 |
| Q5 | 跨群組 | reload 與 call graph 分析是否衝突？ | ❌→已補強 | 新增並行安全模型段落 |
| Q6 | 跨群組 | checkout 後是否自動重載快取？ | ⚠️→已補強 | 明確說明 checkout 不觸發重載 |
| Q7 | 業務規則 | repo URL 為空值時的啟動行為？ | ❌→已補強 | 新增 URL 為空的 repo 段落 |
| Q8 | 業務規則 | remote branch 不存在時 clone 行為？ | ❌→已補強 | 併入操作前置條件段落 |

---

## 注意事項

- clone/pull/checkout 操作依賴環境變數 GIT_USERNAME、GIT_TOKEN 進行認證
- repo 設定完全由 application.yml 驅動，不需改 Java 程式碼即可新增 repo
- ⚠️ checkout 後未 reload 快取是已知行為，可能導致分析結果與實際 branch 不符
