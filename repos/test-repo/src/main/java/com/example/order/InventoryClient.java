package com.example.order;

import java.util.List;

public interface InventoryClient {

    void assertAvailable(List<OrderController.OrderItemRequest> items);

    void reserve(String orderId, List<OrderController.OrderItemRequest> items);
}
