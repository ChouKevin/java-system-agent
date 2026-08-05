# Java System Agent V2 架構設計

> 狀態：設計定稿，待轉為實作計畫
>
> 範圍：Java codebase analysis、knowledge 文件查詢、可診斷 trace
>
> 核心邊界：所有 Java 程式語意解讀由 `java-code-intelligence`（JDT LS / JDT Core）負責

## 1. 目的與背景

目前專案尚未正式運行，因此 V2 不需要維持既有 Agent orchestration 的相容性，可以直接重整責任邊界、domain model、trace persistence 與 package 結構。

V2 要解決的核心問題是：使用者以自然語言詢問一個或多個 Java repository 的行為，系統必須在明確的 repository revision 上取得可驗證的語意證據，產出附有 evidence reference 的回答，並保留足以重建執行狀態的診斷資料。

這不是讓 LLM 自由操作程式碼工具的通用 Agent。V2 採用 bounded adaptive planning：LLM 只描述「還需要什麼資訊」，deterministic runtime 決定可以使用哪些已註冊 capability、如何更新狀態，以及何時結束。

## 2. 已確認的設計決策

1. 直接取代既有 Agent orchestration，不做 shadow mode 或 legacy compatibility。
2. Java 定義、引用、實作、呼叫鏈、資料存取、交易、Feign/HTTP、MQ 等語意，一律交由 `java-code-intelligence` 解讀。
3. Agent 不解析 Java、不自行推導 call graph，也不把低階 JDT 操作暴露給 LLM。
4. 一次使用者訊息建立一個 `AnalysisRun`；同一 Run 可以因 revision 改變而建立新的 `AnalysisAttempt`。
5. repository scope 必須支援多選，且允許分析途中根據語意證據擴大範圍。
6. 每個 Attempt 對每個 repository 固定一個 revision；revision 改變時不能混用新舊 evidence。
7. 一般追問先檢查 Semantic Service 已知的最新 analyzed revision；revision 改變則重新驗證。
8. 只有使用者明確要求「更新／抓最新」時才觸發 repository refresh，不在一般分析流程隱式修改 repository 狀態。
9. 初版 knowledge 查詢使用檔案、metadata、section 與 lexical search，不導入 RAG。
10. 文件不足或查詢能力不足必須被記錄，但 runtime 不直接修改正式 `knowledge/` 文件。
11. MongoDB trace 是 Agent 診斷 replay 的 system of record；OpenTelemetry/Micrometer 只負責觀測指標與 span。
12. 初期 Trace 與 knowledge-gap 查詢 API 不做權限驗證，方便架構與功能開發；正式部署前再補安全邊界。
13. Semantic Service 仍在其他工作流開發。V2 先透過 Fake `SemanticQueryPort` 驗證核心，不提前強迫其 API 或模組結構定案。
14. Process crash 後初版不自動續跑；durable workflow 明列為未來方向。

## 3. 範圍與非目標

### 3.1 初版範圍

- Slack 與 REST 接收問題。
- 問題理解、資訊需求拆解與 repository scope 選擇。
- 單一或多 repository 的 revision-bound Java semantic analysis。
- 既有 `knowledge/` 文件查詢與回答上下文補充。
- Evidence-bound answer composition 與 claim verification。
- 完整 execution trace、artifact、state snapshot 與診斷 replay。
- 文件缺口與 capability 缺口記錄。
- 開發用途的 Run、timeline、state、artifact、knowledge-gap 查詢 API。

### 3.2 非目標

- 不由 Agent 自己解析 Java AST、symbol、call graph 或 cross-service route。
- 不讓 LLM 任意選 tool、組 HTTP request、指定 repository mutation 或直接改狀態。
- 不在一般 code analysis 中自動 clone、pull、checkout repository。
- 不在初版導入 RAG、vector database、長期記憶或自我學習 loop。
- 不在初版支援 process crash 後自動續跑。
- 不在初版完成 production-grade API authentication/authorization。
- 不在 Semantic Service 開發尚未穩定時，將候選需求誤寫成已存在的正式 API。
- Log/APM 查詢、真實業務 API 呼叫與環境差異分析留待後續。

## 4. 系統責任邊界

```text
Slack / REST
    |
    v
Agent Application
    |- Conversation context：整理前次 Run 的有限摘要與 references
    |- Understanding：將問題轉成結構化意圖與 InformationNeed
    |- Scope：多選 repository、維護 RevisionVector
    |- Runtime：規劃、執行、reducer、goal、budget、retry
    |- Knowledge：查詢 knowledge/，記錄文件缺口
    |- Answer：組合回答並驗證 claims
    `- Trace：event、snapshot、artifact、diagnostic replay
              |
              v
SemanticQueryPort
              |
              v
java-code-intelligence
    |- repository workspace / revision readiness
    |- JDT LS / JDT Core lifecycle
    |- symbol、definition、reference、implementation
    |- entry point、call graph、data access、transaction
    |- Feign/HTTP route、MQ producer/consumer 等跨服務語意證據
    `- source range、warning、confidence、explainable evidence
```

### 4.1 Agent 負責

- 理解使用者意圖，但不直接宣告程式語意事實。
- 決定需要哪些類型的資訊，並驗證 capability 是否允許執行。
- 維護 Run、Attempt、repository scope、revision、budget 與狀態機。
- 呼叫 Semantic Service 的高階、revision-bound capability。
- 將 semantic results 保存為 artifact/evidence reference。
- 將多 repository、多來源 evidence 組成回答。
- 驗證每個重要 claim 是否由 evidence 支持。
- 查詢文件、記錄缺口、保存 trace 與提供診斷查詢。

### 4.2 Semantic Service 負責

- Java source 與 build/workspace 的語意載入。
- JDT LS/JDT Core 的啟動、同步、timeout 與錯誤轉譯。
- Java symbol、method、reference、implementation 與 source range 判定。
- call graph、transaction、data access 與跨服務連結的程式語意推導。
- 回傳 revision-bound、可解釋、可引用的 evidence。
- 對 ambiguous symbol、partial result、not ready、revision mismatch 等情況提供明確狀態。

### 4.3 禁止跨越的邊界

- Agent 不可因 Semantic Service 缺功能而改用 regex、parser 或 LLM 猜測 Java 語意。
- LLM 不可直接呼叫 repository management API。
- LLM 不可直接產生 authoritative state transition。
- Agent 不可把舊 revision evidence 當作新 Attempt 的有效證據。
- Semantic Service 的 artifact identifier 與 Agent artifact reference 不可混為同一概念。

## 5. 高階元件架構

```text
Inbound adapters
  Slack / REST
        |
        v
AnalysisApplicationService
        |
        +--> ConversationContextBuilder
        +--> RepositoryScopeResolver
        +--> AnalysisRunFactory
        |
        v
BoundedAnalysisRuntime
  +-----------------------------+
  | GoalEvaluator               |
  | InformationNeedPlanner      |
  | SemanticCapabilityRegistry  |
  | CapabilityExecutor          |
  | StateReducer                |
  | BudgetPolicy / RetryPolicy  |
  | RunFinalizer                |
  +-----------------------------+
        |                 |
        |                 +--> KnowledgeQueryPort
        +---------------------> SemanticQueryPort
        |
        +--> ReasoningPort / AnswerPort / ClaimVerificationPort
        |
        `--> TraceStore / ArtifactStore / KnowledgeGapStore
```

### 5.1 Hexagonal boundary

Domain 與 application layer 只依賴 ports：

- `SemanticQueryPort`
- `KnowledgeQueryPort`
- `QuestionUnderstandingPort`
- `AnswerCompositionPort`
- `ClaimVerificationPort`
- `TraceStore`
- `ArtifactStore`
- `KnowledgeGapStore`
- `RepositoryRefreshPort`
- `Clock` 與 identifier provider

Adapters 可以替換為 HTTP、Spring AI、MongoDB、filesystem、Slack 或 fake implementation。核心測試不需要啟動 JDT LS、真實 LLM 或 MongoDB。

## 6. LLM 使用邊界

### 6.1 必須使用 LLM

| 階段 | 用途 | 輸入 | 結構化輸出 |
|---|---|---|---|
| Question understanding | 理解意圖、拆分子問題與資訊需求 | 使用者問題、有限 conversation context、repo 摘要 | intent、candidate repos、`InformationNeed[]` |
| Answer composition | 將 evidence 組成可讀回答 | 已選 evidence、knowledge excerpts、限制條件 | answer、claim-to-evidence mapping |
| Semantic claim verification | 檢查回答是否超出 evidence | answer claims、evidence metadata | verified/unsupported claims、修正建議 |

一般成功 Run 預期需要三次必要 LLM call。

### 6.2 條件式使用 LLM

當 deterministic ranking 無法安全選出唯一候選時，才使用 LLM 做 repository、symbol 或 semantic target disambiguation。輸出仍需通過 schema 與候選集合驗證，LLM 不得創造不存在的 target。

若回答驗證失敗且 budget 允許，可以再執行一次「補充 need → 重組回答 → 再驗證」；因此 replan 路徑通常增加兩次 LLM call。

### 6.3 不使用 LLM

- Goal 判定與 terminal status。
- Planner 對 capability 的選擇與 prerequisite 驗證。
- State reducer 與 state revision。
- Revision freshness、pinning 與 mismatch 處理。
- Retry、timeout、budget、cancellation。
- JDT/Java semantic analysis。
- Evidence reference 建立與 artifact persistence。
- Trace event、snapshot、replay。
- Knowledge gap 規則判定與聚合。
- Repository refresh 的授權判定。

### 6.4 LLM 安全限制

- 全部輸出採 typed structured output，解析失敗只能走有限 repair。
- LLM 回傳的 repository、symbol 與 target 候選必須存在於 Runtime 提供的候選集合，或通過 repository registry 驗證。
- LLM 只能提出 `InformationNeed`，不能指定任意 class/method/URL 來執行未註冊工具。
- Prompt、response 與 repair 結果可以保存為已分類、去敏感資訊的 artifact，但不得保存隱藏 chain-of-thought。
- LLM 產生的 claim 在驗證前一律不是 authoritative fact。

## 7. Domain Model

以下模型以用途為主，不綁定特定 Java class 形式。

### 7.1 `AnalysisRun`

代表一次使用者訊息所產生的完整分析工作。保存原始問題 reference、conversation reference、目前狀態、Attempt 清單、最終回答與 terminal outcome。

一個 Run 可以包含多個 Attempt，但對使用者仍是一個問題與一個最終回答。

### 7.2 `AnalysisAttempt`

代表在一組固定 revision 條件下的一次分析嘗試。它擁有自己的 `RevisionVector`、evidence、state revisions、budget 與 outcome。

當任何已選 repository revision 改變，舊 Attempt 結束為 `STALE`，新 Attempt 重新驗證，不在同一 Attempt 中替換 revision。

### 7.3 `AnalysisState`

Runtime 唯一 authoritative execution state。包含目前 goal progress、repository scope、revision vector、pending needs、resolved facts、evidence refs、warnings、budget 與狀態版本。

所有更新只能由 deterministic `StateReducer` 套用 typed event 完成。

### 7.4 `RepositoryScope`

本次分析涉及的 repository 集合，不是單一 repository 欄位。每個選項記錄：

- repository identity
- selection reason
- required/optional
- discovery source
- semantic readiness
- repository-specific warnings

分析中若 Semantic Service 找到 Feign、HTTP 或 MQ 跨服務目標，可以新增 repository，但必須留下 scope expansion event。

### 7.5 `RevisionVector`

將每個 repository 對應到此 Attempt 固定使用的 revision：

```text
{
  order-service -> 8a51f2...
  payment-service -> c1970b...
  notification-service -> 1fe301...
}
```

同一 Attempt 中既有 repository 的 revision 不可被替換；只允許為新發現的 repository 增加首次 pinning。

### 7.6 `InformationNeed`

描述「為了回答問題還缺哪一類資訊」，例如：

- 找到某 entry point
- 確認某方法實作
- 取得跨服務呼叫目標
- 取得 transaction boundary
- 取得 knowledge 文件中某業務背景

它不包含任意可執行程式碼，也不直接等同於 tool call。

### 7.7 `SemanticTarget`

Agent 用來重新定位 semantic query 的穩定座標，例如 repository、revision、symbol key、route key、entry point key 或 source range。它是查詢座標，不是 Agent artifact ID。

### 7.8 `SemanticCapability`

Runtime 可執行的已註冊能力，包含：

- capability name/version
- 支援的 `InformationNeed` 類型
- input/output schema
- prerequisite
- request mapper 與 response mapper
- 預估成本
- timeout/retry/failure policy

新增 Semantic Service API 時，通常只需新增或擴充 capability adapter，不需要把控制權交給 LLM。

### 7.9 `EvidenceRef`

指向可支持 claim 的 evidence artifact，記錄來源 service、repository、revision、semantic target、source range、confidence、warning 與 artifact digest。

回答中的重要 claim 必須能映射到至少一個 `EvidenceRef`；跨 repository claim 通常需要多個 refs 或一個由 Semantic Service 明確產生的 cross-repo evidence。

### 7.10 `ArtifactRef`

指向 Agent 保存的大型或外部資料，例如 semantic response、knowledge excerpt、LLM structured output、answer draft。Artifact 採 content-addressed 儲存並依類型設定 retention/TTL。

`ArtifactRef` 只屬於 Agent storage；後續重新查 Semantic Service 時使用 `SemanticTarget + revision`，不是拿 artifact ID 當 Semantic Service 查詢參數。

### 7.11 `Goal`

描述回答完成條件，例如必要子問題已解決、關鍵 claim 有 evidence、沒有 unresolved blocking need。GoalEvaluator 是 deterministic policy。

### 7.12 `AnalysisOutcome`

主要 terminal outcome：

- `COMPLETED`：有足夠 evidence，回答已驗證。
- `INCONCLUSIVE`：系統正常運作，但 evidence、文件或 capability 不足以可靠回答。
- `FAILED`：系統或 invariant 發生錯誤，無法正常完成。
- `CANCELLED`：使用者或上游取消。

`INCONCLUSIVE` 是合法結果，不應被誤算為系統 failure。

### 7.13 `AttemptOutcome`

描述單次 revision-bound 嘗試的結果：

- `COMPLETED`：此 Attempt 已完成並可供 Run 產生最終結果。
- `STALE`：revision mismatch 或 freshness check 證明本次 evidence set 已失效。
- `INCONCLUSIVE`：本次嘗試沒有足夠 evidence，但不是系統錯誤。
- `FAILED`：本次嘗試遇到 invariant、contract 或 persistence failure。
- `CANCELLED`：處理被取消。

Run 與 Attempt outcome 分開保存，避免把「某次 revision 已過時」誤認為整個使用者問題失敗。

### 7.14 `KnowledgeGap`

記錄文件或 knowledge 查詢能力為何無法支持問題，包含 category、問題摘要、repo scope、相關文件、缺少內容、出現次數、最近 Run 與 review status。

它是改善文件的 feedback，不是 Runtime 直接改寫文件的指令。

## 8. Bounded Adaptive Planning

### 8.1 Planner 流程

```text
LLM understanding
  -> typed InformationNeed[]
  -> schema validation
  -> prerequisite validation
  -> deterministic capability selection
  -> budget / retry / revision validation
  -> capability execution
  -> result normalization
  -> reducer event
  -> goal evaluation
```

### 8.2 Planner 不負責的事情

- 不直接更新 state。
- 不解讀 Java response 的隱含意義。
- 不自行組任意 HTTP call。
- 不因 capability 缺失而 fallback 到 LLM 猜測。
- 不在同一 Attempt 中偷偷換 revision。

### 8.3 Capability 擴充性

這個模式保留自由 tool-calling 方案的大部分彈性：新增能力時可以註冊新的 need、schema、mapper 與 failure policy；同時保留 deterministic 安全性、可測試性與 replay 能力。

### 8.4 No-progress 保護

每次 transition 必須至少新增一項有效進展：resolved need、new evidence、scope expansion、warning resolution 或 terminal diagnosis。連續迭代沒有進展、重複得到相同 evidence，或 budget 用盡時，Run 以 `INCONCLUSIVE / NO_PROGRESS` 結束，而不是無限 loop。

## 9. 多 Repository 與跨服務分析

### 9.1 Repository 選擇必須是多選

微服務邊界不一定切得乾淨。初始 scope resolver 可以根據 repository metadata、knowledge service map、使用者明示名稱與問題內容產生候選集合；若多個 repository 都可能相關，保留多選而不是過早壓成唯一 repo。

### 9.2 分析途中擴張

例如使用者問「建立訂單後為什麼會寄通知」：

1. 初始選到 `order-service`。
2. Semantic Service 證明程式碼發出 `OrderCreated` MQ event。
3. cross-repo evidence 指向 `notification-service` consumer。
4. Runtime 將 `notification-service` 加入 scope 並 pin revision。
5. 後續查詢在同一 Attempt 的 RevisionVector 下進行。

只有 Semantic Service 或可信 repository/knowledge metadata 可以建立 cross-repo link；Agent/LLM 不能靠名稱相似度宣告服務關係已成立。

### 9.3 Revision consistency

- 每個 semantic request 必須帶 expected revision，或由 adapter 確保等價約束。
- response 必須能回報 analyzed revision。
- expected 與 analyzed 不一致時，不接納 response 為有效 evidence。
- mismatch 結束目前 Attempt 為 `STALE`。
- Coordinator 最多自動建立一個新 Attempt，避免 revision 持續變動造成無限重試。
- 新 Attempt 可以帶入 intent、repo candidates、semantic coordinates，但舊 evidence 只保留為歷史資料，不能支持新回答。

## 10. Conversation 與 Freshness

### 10.1 Conversation 模型

每個訊息建立新的 Run。後續問題不直接塞入完整歷史 prompt，而由 `ConversationContextBuilder` 讀取前一個已完成 Run 的有限上下文：

- 回答摘要
- 已選 repository 與 revisions
- semantic targets
- artifact/evidence refs
- unresolved assumptions

這些 refs 讓系統可以按需載入細節，避免 prompt 無限制成長。

### 10.2 一般追問

當使用者問「那第二步呢？」或認為回答過時：

1. 建立新 Run。
2. 從前次 Run 取得 coordinates 與 revision。
3. 向 Semantic Service 查詢 repository 目前可分析的 revision/readiness。
4. 若 revision 未變，可重用適當的 context refs，並按需要重新查詢。
5. 若 revision 已變，建立新 Attempt，在新 RevisionVector 下重抓證據並重新驗證回答。

### 10.3 明確更新最新版本

當使用者明確說「同步最新」、「重新 pull」、「用 remote 最新版本」時：

1. Application 進入獨立 `RepositoryRefreshUseCase`。
2. 呼叫 management port 執行 ensure/sync/checkout 類操作。
3. Refresh 完成後再建立新的分析 Attempt。
4. Refresh event 與 analysis event 分開保存。

一般 analysis capability 不得隱式呼叫 refresh。

## 11. Semantic Service Integration

### 11.1 目前可觀察到的能力

依目前 `java-code-intelligence` OpenAPI 與程式結構，已可見的方向包括：

- repository list/get/ensure/sync/checkout
- revision-bound repository readiness
- entry point listing
- call graph 與 flattened call graph
- cross-repository route lookup/suggest
- `expectedRevision` / `analyzedRevision`
- SUCCESS、PARTIAL、FAILED、BUSINESS_READ_FORBIDDEN 類結果
- revision mismatch、busy/not-ready、ambiguous method、symbol not found、engine timeout/protocol error
- evidence、source ranges、warning/error、confidence、strategy

這份設計只把它們視為目前盤點結果；Semantic Service 穩定前，Agent 不應依賴尚未正式確認的細節。

### 11.2 Agent 第一階段整合策略

1. 定義最小 `SemanticQueryPort` 與 normalized result model。
2. 使用 Fake implementation 建立完整成功、partial、ambiguous、mismatch 與 timeout 測試。
3. 先驗證 Agent runtime、revision invariant、multi-repo、trace 與 answer lifecycle。
4. Semantic Service 開發告一段落後重新盤點 OpenAPI。
5. 將需求分類為：既有 API 可直接提供、既有 API 可組合、需要補強、延後。
6. 最後才完成 HTTP adapter 與跨服務 contract test。

### 11.3 預計補強方向，不是既有承諾

- 查詢 repository 當前 local analyzed revision 的輕量 probe。
- 明確的 cross-repo semantic target/evidence model。
- HTTP/Feign、MQ producer/consumer、transaction/data-access 的統一高階 capability。
- 一次攜帶多 repository revision constraints 的 batch/query context。
- 可延續查詢的穩定 semantic coordinates。
- capability/version discovery，讓 Agent adapter 能拒絕不相容版本。
- 統一 partial、ambiguity、not-ready、forbidden 與 retry hint。

這些項目在 Semantic Service 穩定後重新評估，不預先綁定 endpoint path 或 payload。

## 12. Knowledge 查詢與文件缺口

### 12.1 正式 knowledge 結構

正式、人工維護的文件仍放在：

```text
knowledge/
  service-map.md
  repos/{repoId}/
    business-map.md
    summary.md
    business-groups/*.md
```

初版 `KnowledgeQueryPort` 提供 deterministic metadata、section、path-safe file read 與 lexical search。LLM 不直接取得任意 filesystem tool，也不導入 embedding/vector search。

### 12.2 文件與語意的分工

- Knowledge 文件：業務目的、術語、服務職責、人工整理的流程背景。
- Semantic Service：目前 revision 上的 Java 程式事實。
- 回答組合：可以同時引用兩者，但必須區分文件敘述與程式證據。

文件與 code evidence 衝突時，回答必須揭露衝突並記錄可能 stale 的 knowledge gap，不可默默選一邊。

### 12.3 Gap 偵測時機

`RunFinalizer` 在 Run 到達 terminal outcome 後觸發 `KnowledgeGapDetector`。Detector 採 deterministic 規則，不呼叫 LLM，也不影響已完成的使用者回答。

### 12.4 Gap 分類

| Category | 說明 | 去向 |
|---|---|---|
| `KNOWLEDGE_MISSING` | 應有業務背景但文件不存在 | 文件 backlog |
| `KNOWLEDGE_AMBIGUOUS` | 文件說法無法唯一解釋 | 文件 backlog |
| `KNOWLEDGE_STALE` | 文件與 revision-bound evidence 衝突 | 文件 backlog |
| `KNOWLEDGE_SEARCH_MISS` | 文件可能存在但目前檢索不到 | 查詢品質 backlog |
| `CROSS_SERVICE_LINK_MISSING` | 文件缺少跨服務關係 | 文件/service-map backlog |
| `KNOWLEDGE_QUERY_CAPABILITY_MISSING` | 初版 lexical/section 查詢不足 | knowledge capability backlog |
| `SEMANTIC_CAPABILITY_MISSING` | 需要的 Java 語意能力不存在 | semantic integration backlog |

Infrastructure failure、permission forbidden、user cancellation 與普通 timeout 不歸類為文件缺口。

### 12.5 Review 與回饋流程

1. Runtime 將 gap 保存於 MongoDB 並依 fingerprint 聚合次數。
2. 開發者從 open trace/gap API 檢視高頻或高影響 gap。
3. 人工觸發 review/export 時，才可產生 `knowledge/review/gaps/**` 草稿。
4. Review 區不參與正常 knowledge query。
5. 人工核准後更新正式文件。
6. 用歷史問題 replay 驗證回答品質是否改善。

Runtime 不直接寫入正式 `knowledge/`，避免未驗證內容污染後續回答。

## 13. Runtime State Machine

### 13.1 建議狀態

```text
RECEIVED
  -> UNDERSTANDING
  -> SCOPE_RESOLVING
  -> REVISION_PINNING
  -> PLANNING
  -> EXECUTING
  -> COMPOSING
  -> VERIFYING
  -> COMPLETED

任何分析狀態可以進入：
  -> INCONCLUSIVE
  -> FAILED
  -> CANCELLED

revision mismatch：
  Attempt -> STALE
  Run -> new Attempt 或 INCONCLUSIVE
```

### 13.2 Event-before-state rule

每個 authoritative transition 必須：

1. 驗證 command 與 expected state revision。
2. Reducer 產生 typed transition event 與 candidate new state。
3. 先持久化 event。
4. 再發布/採用新的 authoritative state。
5. 依策略建立 snapshot。

核心 transition event 無法寫入 MongoDB 時，Run 以 `FAILED / TRACE_PERSISTENCE_FAILED` 結束。因為沒有可靠 trace，就不能宣稱狀態已完成。

## 14. Trace、Artifact 與 Diagnostic Replay

### 14.1 MongoDB collections

| Collection | 用途 |
|---|---|
| `analysis_runs` | Run 摘要、問題 reference、terminal outcome、final answer ref |
| `analysis_attempts` | 每次 Attempt、RevisionVector、budget、outcome |
| `analysis_events` | append-only typed events |
| `analysis_state_snapshots` | 指定 state revision 的快照 |
| `analysis_artifacts` | semantic/knowledge/LLM/answer 等大型內容 |
| `knowledge_gaps` | gap 聚合、狀態與相關 Run |

### 14.2 Event envelope

每個 event 至少包含：

- schema version
- event type/version
- run ID / attempt ID
- trace ID / span ID
- monotonic sequence
- previous/new state revision
- timestamp
- actor/component
- correlation/causation ID
- payload 或 artifact refs

Event payload 必須可版本化；replay 不能依賴目前 class serialization 的偶然格式。

### 14.3 Artifact policy

- 內容以 digest/content address 去重。
- 依 `SEMANTIC_RESULT`、`KNOWLEDGE_EXCERPT`、`LLM_INPUT`、`LLM_OUTPUT`、`ANSWER` 等類型分類。
- 在寫入前執行 secret/token/header redaction。
- 依類型配置 TTL；final answer 與必要 evidence 可保留較久。
- 大型內容未來可以移至 object storage，MongoDB 只保留 metadata/ref。
- 不保存隱藏 chain-of-thought。

### 14.4 Diagnostic replay

Replay 的目標是：給定 Run、Attempt 與 state revision，從最近 snapshot 加上後續 events 重建當時的 `AnalysisState`，供除錯、稽核與歷史問題重跑比較。

初版 replay 不代表 process crash 後自動續跑，也不會重送外部 semantic/LLM call。

### 14.5 OpenTelemetry / Micrometer

OTel/Micrometer 記錄 latency、error rate、token/cost、capability duration、retry 與 correlation span。Exporter 失敗只標記 observability degraded，不影響 MongoDB authoritative state。

### 14.6 開發期開放 API

初版先不做 auth：

```text
GET /internal/v2/runs
GET /internal/v2/runs/{runId}
GET /internal/v2/runs/{runId}/timeline
GET /internal/v2/runs/{runId}/attempts/{attemptId}/state/{revision}
GET /internal/v2/artifacts/{artifactId}
GET /internal/v2/knowledge-gaps
GET /internal/v2/knowledge-gaps/{gapId}
```

即使無 auth，仍必須做 artifact classification、secret redaction、path validation、response size limit 與明確的 development-only configuration。正式部署前，trace/artifact API authentication/authorization 是 blocker。

## 15. Error、Retry 與 Terminal Status

| 情況 | 行為 | Outcome |
|---|---|---|
| Ambiguous repository/target | deterministic ranking；必要時一次條件式 LLM disambiguation | 繼續或 `INCONCLUSIVE` |
| Semantic partial result | 接納明確標示的有效 evidence，保留 warning | 視 goal 而定 |
| Symbol not found | 允許替代 need/candidate；受 budget 限制 | `INCONCLUSIVE` if unresolved |
| Revision mismatch | 拒絕 evidence，Attempt `STALE`，最多建立一次新 Attempt | 繼續或 `INCONCLUSIVE` |
| Semantic busy/not-ready | 依 retry hint 做 bounded retry | 繼續或 `INCONCLUSIVE` |
| Semantic timeout | bounded retry；耗盡後記錄 infrastructure diagnosis | `INCONCLUSIVE` |
| Semantic protocol/contract error | 不猜測 response；停止目前處理 | `FAILED` |
| Business read forbidden | 不繞過權限、不記成 doc gap | `INCONCLUSIVE` |
| Unsupported semantic capability | 記錄 semantic capability gap | `INCONCLUSIVE` |
| Knowledge 不足 | 回答揭露限制並記錄 knowledge gap | 可 `COMPLETED` 或 `INCONCLUSIVE` |
| Answer claim unsupported | 補查一次或移除/弱化 claim | `COMPLETED` 或 `INCONCLUSIVE` |
| No progress / budget exhausted | 停止 loop | `INCONCLUSIVE / NO_PROGRESS` |
| Reducer invariant / state corruption | 停止處理 | `FAILED` |
| Trace persistence failure | 不發布新 authoritative state | `FAILED / TRACE_PERSISTENCE_FAILED` |
| User cancellation | 停止新外部呼叫，保存已完成 trace | `CANCELLED` |

Retry 必須基於 error classification，不可對所有錯誤盲目重試。

## 16. 一個多 Repository 對話範例

### 16.1 第一次問題

```text
使用者：建立訂單後，系統怎麼通知會員？
```

```text
Run R1 / Attempt A1
1. LLM understanding：需要 entry point、後續呼叫/MQ、通知 consumer。
2. Scope resolver：初選 order-service。
3. Pin order-service@rev-O1。
4. Semantic Service：Order API -> application service -> publish OrderCreated。
5. Response 提供 cross-repo target，指向 notification-service consumer。
6. Scope expansion：加入 notification-service@rev-N1。
7. Semantic Service：consumer -> template selection -> provider adapter。
8. Answer composition：產生回答與 EvidenceRef mapping。
9. Claim verification：確認每個重要步驟都有 revision-bound evidence。
10. R1 COMPLETED。
```

回答只引用 Agent 保存的 evidence refs；若使用者打開細節，Agent 讀取 refs 指向的 artifacts。需要重新問 Semantic Service 時，使用 repository、revision 與 semantic coordinates，不使用 artifact ID。

### 16.2 第二次追問且 revision 已改變

```text
使用者：這份回答看起來是舊的，現在還會走同一個通知 provider 嗎？
```

```text
Run R2
1. 讀取 R1 的摘要、repos、revisions、semantic targets、evidence refs。
2. 查 Semantic Service readiness/current analyzed revision。
3. 發現 notification-service 已從 rev-N1 變成 rev-N2。
4. 建立 Attempt A1，RevisionVector 使用 order-service@rev-O1、notification-service@rev-N2。
5. 舊的 notification evidence 只保留為歷史，不支持 R2 claim。
6. 重新查 consumer 與 provider path。
7. 重組並重新驗證回答，明確說明 revision 差異。
```

若使用者其實要求 remote 最新版本，系統先執行獨立 refresh use case，再建立分析 Attempt。

## 17. 專案結構調整方向

### 17.1 最終建議結構

長期可以整理為同一 monorepo 下的 Maven aggregator：

```text
pom.xml
agent-app/
java-semantic-service/
knowledge/
docs/
docker-compose.two-services.yml
```

但 `java-code-intelligence` 目前仍在其他工作流開發，因此初版不先搬移其目錄、parent POM 或正式 contract。先保持服務獨立，透過 port/fake 隔離變動。

### 17.2 Agent application 建議 slices

```text
agent-app
  analysis/
    domain/
    application/
    port/in/
    port/out/
  semantic/
    adapter/http/
    adapter/fake/
  reasoning/
    adapter/springai/
  knowledge/
    query/
    gap/
    adapter/filesystem/
  tracing/
    adapter/mongo/
    replay/
  conversation/
  slack/
  api/
  configuration/
```

模組邊界可先由 package 與 Spring Modulith 驗證，等責任穩定後再決定是否拆成更多 Maven modules。

### 17.3 既有程式碼處置

預計移除或取代：

- 舊 `ai.loop` orchestration
- 可讓 LLM 自由選工具的 `ai.tools`
- 舊 evidence model
- root app 內的 Java parser、call graph、trie/code search
- root app 內的 repository/JGit lifecycle
- 舊單文件式 trace 與相依 controller

可以保留並重新歸位：

- Slack inbound/outbound integration
- Spring AI 基礎設定
- MongoDB 基礎設定
- rate limiting
- `knowledge/` 文件與 safe path validation
- 共用的非語意 transport/configuration utilities

既有 `DocumentTools` 應改為 deterministic `KnowledgeQueryPort` adapter，不再直接暴露 filesystem tool 給 LLM。

### 17.4 Modulith 事件邊界

`analysis` 在 terminal 時發布 `AnalysisConcludedEvent`；`knowledge.gap` 消費這個事件並執行 gap detection，避免 analysis domain 反向依賴 knowledge implementation。Trace persistence 的核心 transition 則保持同步，不透過 eventual event 取代 authoritative write。

## 18. 測試策略

### 18.1 Unit tests

- Planner：need 對 capability、prerequisite、cost、missing capability。
- Reducer：state revision、typed event、evidence、repo/revision invariant。
- RepositoryScope：多選、新 repo 擴張、invalid cross-link rejection。
- GoalEvaluator：completed、inconclusive、no-progress、budget exhausted。
- KnowledgeGapDetector：分類、排除 infrastructure/forbidden/cancelled、fingerprint 聚合。
- ConversationContextBuilder：只帶有限 refs，不洩漏完整歷史。

這些測試不需要真實 LLM 或 JDT LS。

### 18.2 Adapter / integration tests

- Spring AI adapter：Fake ChatModel structured output、repair、prompt artifact/redaction。
- MongoDB：event-first transition、snapshot replay、artifact digest/TTL、gap aggregation、open trace APIs。
- Knowledge filesystem：metadata、section、lexical search、safe path、review 區排除；不測 RAG。
- Semantic HTTP adapter：等 Semantic Service contract 穩定後，用 mock HTTP 驗證 revision/error/result mapping。
- 最後增加 Agent + Semantic Service 的 two-service contract/end-to-end test。

### 18.3 必要情境

1. 單 repository 成功回答。
2. 一開始即選多 repository。
3. 由 HTTP/Feign evidence 發現新 repository。
4. 由 MQ evidence 發現 producer/consumer repository。
5. 同 revision 的追問重用有限 context。
6. revision 已更新時重新查詢與驗證。
7. 使用者明確要求 refresh 後再分析。
8. ambiguous candidate 觸發條件式 LLM disambiguation。
9. partial semantic result 仍可產生有警告的回答。
10. revision mismatch 結束舊 Attempt 並建立第二 Attempt。
11. claim evidence 不足時進行一次 bounded replan。
12. no progress 以 `INCONCLUSIVE` 結束。
13. 文件不清楚時正確記錄/聚合 knowledge gap。
14. trace persistence failure 使 Run `FAILED`。
15. 從 snapshot + events 重建指定 state revision。
16. cancellation、timeout、retry 與 budget limit。

真實 LLM 與 JDT LS 只放在非 deterministic smoke/end-to-end suite，不進一般單元測試的必要路徑。

## 19. 初版驗收條件

- LLM 無法直接選擇任意 port、變更 state 或 mutation repository。
- 一般成功 Run 的必要 LLM call 為 understanding、composition、verification 三類。
- Repository scope 支援多選與分析途中擴張。
- 每個 Attempt 對每個 repository 保持 revision consistency。
- Revision 改變後不會用舊 evidence 支持新回答。
- 每個重要回答 claim 可追到 `EvidenceRef`。
- Root Agent application 不再自行解析 Java 或建立 Java call graph。
- Fake Semantic Port 足以驗證核心 runtime 與所有主要 outcome。
- MongoDB events/snapshots 可以重建指定 state revision。
- 開發者可以透過未驗證的 internal API 查看 Run、timeline、state、artifact 與 gaps。
- 文件不足與 semantic capability 不足會分流記錄。
- 初版 knowledge query 不依賴 RAG。
- Semantic Service API 未穩定時，Agent core 不被 HTTP contract 細節綁死。

## 20. 未來方向

依優先順序評估：

1. Durable workflow：process crash 後安全續跑、外部 call idempotency、lease 與 recovery policy。
2. Trace/artifact API authentication、authorization、audit 與環境隔離。
3. Semantic Service 穩定後重新盤點 capability、formal contract 與 compatibility policy。
4. 以 gap/search metrics 證明需要後，再導入 RAG 或 hybrid retrieval。
5. 大型 artifact 移至 object storage。
6. 在 revision consistency 與 budget 可控下平行執行獨立 semantic capabilities。
7. Log/APM 查詢、真實 API 呼叫與環境差異分析。
8. 由 gap 自動產生文件修改草稿，但仍要求人工核准才能進入正式 `knowledge/`。
9. 用歷史 Run 建立 regression/evaluation dataset，衡量文件與 semantic capability 改善。

## 21. 實作順序原則

這份文件定義架構，不取代逐檔實作計畫。後續計畫應遵守下列順序：

1. 先建立 domain invariants、Fake Semantic Port 與 reducer/goal tests。
2. 再完成 bounded runtime、multi-repo/revision/attempt lifecycle。
3. 接著完成 Mongo trace、artifact、replay 與 internal query APIs。
4. 再接 knowledge query、gap feedback 與 conversation context。
5. 再整合 Spring AI 的三類必要 LLM call。
6. Semantic Service 穩定後才實作正式 HTTP adapter 與 contract tests。
7. 最後接回 Slack/REST，執行 two-service smoke/end-to-end tests。

這個順序讓 Agent 架構能先被 deterministic fakes 驗證，也避免正在演進的 Semantic Service contract 阻塞核心開發。
