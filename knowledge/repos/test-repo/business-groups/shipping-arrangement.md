# 出貨安排

## Business Purpose

This group demonstrates downstream fulfillment work. A shipping task is created only after payment confirmation succeeds, so fulfillment does not begin for unpaid orders.

## Entry Points

| Package | Class | Method | When to inspect |
|---------|-------|--------|-----------------|
| `com.example.order` | `OrderService` | `confirmPayment` | User asks why or when shipping starts. |

## Required Input Data

| Field | Meaning |
|-------|---------|
| `orderId` | Paid order that needs fulfillment. |
| confirmed payment result | Evidence that the order can proceed to shipping. |

## System Behavior

1. Payment confirmation finishes first.
2. The order is marked paid.
3. A shipping task is scheduled for the paid order.
4. The shipping task id is attached to the order record.

## Related Dependencies

| Dependency | Role |
|------------|------|
| `ShippingScheduler` | Creates fulfillment work. |
| `OrderRepository` | Records the shipping task id. |

## Source Lookup

Use `find_call_graph` with:

| repoId | packageName | className | methodSignature |
|--------|-------------|-----------|-----------------|
| `test-repo` | `com.example.order` | `OrderService` | `confirmPayment` |
