package com.example.m6;

import org.springframework.context.event.EventListener;

public final class OrderEventListener {

    @EventListener
    public void onOrderChanged(OrderChanged event) {
        String observedOrderId = event.orderId();
        if (observedOrderId.isBlank()) {
            throw new IllegalArgumentException("order ID must not be blank");
        }
    }
}
