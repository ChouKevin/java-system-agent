# test-repo Business Scope

## Repository Purpose

`test-repo` is a compact order workflow example. It shows where source code and business documents should be placed so the agent can answer questions from repository-level context and then inspect a specific entry point when needed.

## Entry Points

| Business group | HTTP action | Entry point | Required source data |
|----------------|-------------|-------------|----------------------|
| 訂單結帳 | `POST /orders` | `com.example.order.OrderController#createOrder` | customer id, items, shipping address, payment method id |
| 付款確認 | `POST /orders/{orderId}/payments/confirm` | `com.example.order.OrderController#confirmPayment` | order id, payment transaction id |
| 出貨安排 | triggered after payment confirmation | `com.example.order.OrderService#confirmPayment` | paid order, confirmed payment amount |

## Data Locations

| Data | Location |
|------|----------|
| Java source | `repos/test-repo/src/main/java/com/example/order/` |
| Mapper resource | `repos/test-repo/src/main/resources/mapper/OrderMapper.xml` |
| Business group index | `repos/test-repo/docs/business-map.md` |
| Detailed group files | `repos/test-repo/docs/business-groups/*.md` |
