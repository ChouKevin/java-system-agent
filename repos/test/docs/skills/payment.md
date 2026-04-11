# payment

## 概述

付款處理群組，採用策略模式搭配 Spring Profile 切換不同付款實作。

## 進入點

| 類型 | Class | Method | 說明 |
|------|-------|--------|------|
| 內部呼叫 | `com.example.strategy.PaymentService` | `checkout()` | 執行付款與使用者查詢 |

## 策略實作

| Class | Profile | 說明 |
|-------|---------|------|
| `com.example.strategy.StripePaymentStrategy` | `stripe` | Stripe 付款 |
| `com.example.strategy.PayPalPaymentStrategy` | `paypal` | PayPal 付款 |

## 呼叫鏈

```
PaymentService.checkout()
  ├── PaymentStrategy.processPayment("u-1")     [INTERFACE]
  │     ├── StripePaymentStrategy.processPayment()  [Profile: stripe]
  │     └── PayPalPaymentStrategy.processPayment()  [Profile: paypal]
  └── UserMapper.findById(1L)                   [DATA_ACCESS]
        SQL: SELECT * FROM users WHERE id = #{userId}
```
