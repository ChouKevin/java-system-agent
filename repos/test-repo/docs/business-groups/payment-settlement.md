# 付款確認

## Business Purpose

This group confirms that a previously authorized payment has succeeded. Once confirmed, the order is marked as paid and can move into fulfillment.

## Entry Points

| Package | Class | Method | When to inspect |
|---------|-------|--------|-----------------|
| `com.example.order` | `OrderController` | `confirmPayment` | User asks how payment confirmation enters the system. |
| `com.example.order` | `OrderService` | `confirmPayment` | User asks what state changes happen after payment confirmation. |

## Required Input Data

| Field | Meaning |
|-------|---------|
| `orderId` | Order being confirmed. |
| `paymentTransactionId` | Payment transaction returned during checkout. |

## System Behavior

1. Ask the payment provider to confirm the transaction.
2. Mark the order as paid using the confirmed amount.
3. Continue into shipping arrangement by creating a shipping task.
4. Return the order id, shipping task id, and final ready-to-ship status.

## Related Dependencies

| Dependency | Role |
|------------|------|
| `PaymentClient` | Confirms the payment transaction. |
| `OrderRepository` | Stores paid state and confirmed amount. |
| `ShippingScheduler` | Creates fulfillment work after payment succeeds. |

## Source Lookup

Use `find_call_graph` with:

| repoId | packageName | className | methodSignature |
|--------|-------------|-----------|-----------------|
| `test-repo` | `com.example.order` | `OrderController` | `confirmPayment` |
| `test-repo` | `com.example.order` | `OrderService` | `confirmPayment` |
