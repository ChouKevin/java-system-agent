# java-system-agent — 業務摘要

> 本文件根據 business-map.md 與現有業務群組文件整理
> 產出時間：2026-07-27
> 來源同步：2026-07-27
> 目標讀者：PM

## 專案簡介

這個專案正在建立一個能持續理解同一討論串、查詢系統資訊並產生可驗證回答的智慧助理。
目前已完成可啟用的 production composition：可經由 Google Gemini 規劃與驗證回答、呼叫 Java
Semantic Service 查詢程式碼，並將流程可靠保存至 PostgreSQL。但它仍沒有 Slack 或其他外部
事件入口、背景 worker 與回覆傳送，所以尚不能直接提供使用者服務。

## 主要業務功能

### 可靠接收與依序處理問題

同一個來源討論串會累積為一段持續的 session。訊息即使遇到程序重啟也不會遺失；同一
session 會依提問順序處理，不同 session 則可各自等待執行。暫時性基礎設施失敗會有限度
重試；三次外部嘗試後，inbox 會安排第四次 terminal-reconciliation claim，不再呼叫 AI 或查詢
服務。已安全保存但尚未結束的 run 會以 Agent `FAILED` 結束，而 inbox 為 `COMPLETED`；只有
狀態缺失、不安全或 reconciliation 失敗時，inbox 才是 `FAILED`，讓 session 的下一個問題繼續。

### 可驗證的回答流程

AI 負責決定下一步要查詢、回答或請使用者補充，也可以自行選擇一個或多個候選範圍。
系統只負責確認這些選擇是否仍在允許範圍、是否有足夠證據，以及最終回答是否受到證據
支持。驗證可使用 LLM verdict，或在 contract-only 設定下明確回報其 contract basis；系統不使用
信心分數或路由分數；未確定事項會保留成文字說明、警告與候選資訊，
讓後續步驟可以繼續處理疑問。

### 對話歷史與中斷復原

已接受的問答會持續累積，不會被系統刪除、摘要或改寫。處理過程若在答案保存後中斷，
恢復時會沿用原本工作，不會再次要求 AI 做相同決策，也不會重複加入同一段問答。

### 程式碼查詢與程式庫管理邊界

專案透過 versioned HTTP contract 與 opaque `repoId`，向同一 repository 內獨立的 Java
Semantic Service 取得 catalog、exact revision 與五種唯讀程式碼查詢。repository lifecycle
仍由 semantic service 負責，root 不直接操作 clone。
未來若要加入會改變外部狀態的操作，必須另外設計授權、人工批准、重複執行保護與稽核
流程，不能直接當成一般查詢。

## 自動化排程業務

目前沒有啟用中的排程工作。Inbox recovery 與訊息處理只有 application contracts，尚未
接上 scheduler 或 worker。

## 事件驅動業務

目前沒有啟用中的 Slack listener、MQ consumer 或其他外部事件入口。

## 備註

目前仍缺少 Slack、其他外部 ingress、worker/scheduler 與回覆傳送。這份摘要描述已完成且有
測試保護的 composition 與核心行為，不代表服務已可自行接收或傳送使用者訊息。
