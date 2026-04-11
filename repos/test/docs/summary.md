# test-repo 業務摘要

本專案為靜態分析引擎的測試用 fixture，模擬一個包含多種常見 Spring Boot 架構模式的微服務，用於驗證 call graph 分析、進入點掃描與型別解析功能。

## 業務能力

- **訊息佇列消費**：透過 RabbitMQ 接收 demo.queue 訊息並處理
- **訂單管理**：使用 MyBatis-Plus 進行訂單 CRUD 操作，支援依訂單編號查詢
- **付款處理**：策略模式搭配 Spring Profile 切換 Stripe / PayPal 付款實作
- **使用者資料存取**：透過 MyBatis annotation-based mapper 查詢使用者資訊
