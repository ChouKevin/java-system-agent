# Repo 管理邊界

## Business Purpose

Root Agent 只需要知道可查詢的 opaque repository candidates 與執行查詢時的 exact
revision。Clone、pull、checkout、workspace 與 JDT LS lifecycle 屬於獨立
`java-semantic-service`，不在 root runtime 內。

## Current External Entry Points

None. 舊的 root `RepoController`、Git write endpoints 與 startup cache warmer 已不存在。

## Implemented Root Contracts

| Contract | Responsibility | Production status |
|----------|----------------|-------------------|
| `RepositoryCatalogPort` | 提供 runtime-issued repository handles 與描述 | Test fake only |
| `RepositoryRevisionPort` | 在查詢前解析 exact revision，偵測 context drift | Test fake only |
| `RepositoryId` / `RepositoryRevision` | 跨邊界使用的 immutable opaque values | Implemented |

## Runtime Behavior

1. Context issuer 只把 catalog 回傳的 repository candidates 配成 opaque handles。
2. LLM 可選一個、多個或不選候選，並以文字說明範圍疑問。
3. Runtime 驗證選到的 handle 是否真的由本次 context 發出；不替 LLM 增刪、重排或打分。
4. 執行 `QUERY` 前解析 selected repositories 的 exact revision。
5. Revision drift 使舊 attempt 失效；有預算時建立新 attempt，沒有預算時以明確 observation
   結束，不使用過期證據。

## Ownership Boundary

實際 repository lifecycle、source locks 與 semantic snapshot publication 由
`java-semantic-service` 擁有。Root M1 尚無 catalog/revision HTTP adapter，也不會直接讀寫
`repos/` clone。
