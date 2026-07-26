# 程式碼查詢邊界

## Business Purpose

Root runtime 提供 generic `QUERY` action contract，讓 model 選擇 runtime-issued capability
與任意 candidate subset/order。它驗證合約並接收 typed observations/evidence，但不實作
Java call graph、API lookup 或 log query。

## Current External Entry Points

None. 舊的 root `CallGraphController`、`AnalysisController` 與內建分析服務已不存在。
Java semantic HTTP endpoints 位於獨立 `java-semantic-service`；root 尚未有 production
client adapter。

## Implemented Query Flow

1. Runtime 發出 capability descriptors、query schema、candidate handles 與當前 context。
2. LLM 提出一個 `QueryAction`，自行選擇 capability、候選數量與順序，並描述未確定事項。
3. `AgentActionValidator` 驗證 handle membership、schema、scope 與 budget，不做候選推薦或
   semantic ranking。
4. `RepositoryRevisionPort` 綁定 selected repositories 的 exact revision。
5. `AgentSemanticQueryPort` 回傳 typed observations、evidence、warnings 與新 candidates。
6. Runtime 驗證回傳 evidence/revision，配發新的 opaque handles，將完整疑問與證據放入
   後續 model context。
7. `ANSWER` 必須引用已發出的 evidence，且通過 statement-level verification 才能被接受。

## Confidence and Uncertainty

此流程沒有 confidence 欄位、route score 或 ranking。若 route、target、implementation 或
evidence 不確定，adapter/model 必須用 observation、warning、candidate description 與自然
語言表達，不能把疑問壓縮成一個分數。

## Extension Boundary

Java code query、read-only API call 與 log query 可新增 capability/schema 並沿用 `QUERY`。
會修改外部狀態的操作必須使用未來獨立 `EXECUTE` contract，明確處理 authorization、
approval、idempotency、audit 與 reconciliation。
