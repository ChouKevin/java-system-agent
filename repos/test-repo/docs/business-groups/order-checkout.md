# 訂單結帳

## Business Purpose

This group handles the first step of an order: a customer submits items, a shipping address, and a payment method. The system checks inventory, reserves stock, creates a pending order, and starts payment authorization.

## Entry Points

| Package | Class | Method | When to inspect |
|---------|-------|--------|-----------------|
| `com.example.order` | `OrderController` | `createOrder` | User asks how a checkout request enters the system. |
| `com.example.order` | `OrderService` | `createOrder` | User asks what happens after the request is accepted. |

## Required Input Data

| Field | Meaning |
|-------|---------|
| `customerId` | Customer placing the order. |
| `items` | Product id, quantity, and unit price for each item. |
| `shippingAddress` | Destination used later by fulfillment. |
| `paymentMethodId` | Payment instrument used for authorization. |

## System Behavior

1. Reject checkout when no item is provided.
2. Ask inventory to confirm all requested items are available.
3. Calculate total amount from item quantity and unit price.
4. Create a pending order record.
5. Reserve inventory against the order.
6. Start payment authorization and attach the payment transaction to the order.

## Related Dependencies

| Dependency | Role |
|------------|------|
| `InventoryClient` | Checks and reserves stock. |
| `PaymentClient` | Starts payment authorization. |
| `OrderRepository` | Stores order and payment transaction state. |

## Source Lookup

Use `find_call_graph` with:

| repoId | packageName | className | methodSignature |
|--------|-------------|-----------|-----------------|
| `test-repo` | `com.example.order` | `OrderController` | `createOrder` |
| `test-repo` | `com.example.order` | `OrderService` | `createOrder` |
