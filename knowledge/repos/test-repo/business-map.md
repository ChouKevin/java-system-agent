# test-repo Business Map

## Overview

This repository demonstrates an order workflow service. Use this file to choose a business group, then read the matching group document for entry point details.

## Business Groups

| Group name | Description | Detail file |
|------------|-------------|-------------|
| `order-checkout` | Customer submits an order, stock is reserved, and payment authorization is started. | [訂單結帳](business-groups/order-checkout.md) |
| `payment-settlement` | Payment confirmation marks the order as paid and makes it ready for fulfillment. | [付款確認](business-groups/payment-settlement.md) |
| `shipping-arrangement` | A paid order receives a shipping task after payment confirmation. | [出貨安排](business-groups/shipping-arrangement.md) |

## Cross-Group Interactions

| From | To | Reason |
|------|----|--------|
| `order-checkout` | `payment-settlement` | Checkout creates the payment transaction that later needs confirmation. |
| `payment-settlement` | `shipping-arrangement` | Payment confirmation triggers shipping task creation. |

## 進入點快速對照表

| Group name | Class | Method |
|------------|-------|--------|
| `order-checkout` | `com.example.order.OrderController` | `createOrder` |
| `payment-settlement` | `com.example.order.OrderController` | `confirmPayment` |
| `shipping-arrangement` | `com.example.order.OrderService` | `confirmPayment` |
