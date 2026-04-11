# Business Scope

> 專案：test-repo
> 文件版本：2026-04-05 22:30
> 技術棧：Java 17 / Spring Boot 3.2.0 / MyBatis-Plus 3.5.5 / Spring AMQP (RabbitMQ)
> Active Profile：(未設定)

---

## REST API

（本專案無 REST API 進入點）

---

## Scheduled Jobs

（本專案無排程工作）

---

## Message Queue Consumers

| 信心 | Class | Method | 類型 | Topic / Queue | Consumer Group | 業務描述 | Side Effects |
|------|-------|--------|------|---------------|----------------|----------|--------------|
| ✅ | `com.example.listener.MqListenerService` | `onMessage` | RabbitMQ | `demo.queue` | — | 接收 demo queue 訊息並輸出至 console | 無（僅 stdout） |

---

## 未啟用 / 已停用的進入點

| Class | 類型 | 原因 |
|-------|------|------|
| `com.example.strategy.StripePaymentStrategy` | Strategy | `@Profile("stripe")` 未啟用（當前無 active profile） |
| `com.example.strategy.PayPalPaymentStrategy` | Strategy | `@Profile("paypal")` 未啟用（當前無 active profile） |
