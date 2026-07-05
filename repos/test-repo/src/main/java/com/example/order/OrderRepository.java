package com.example.order;

import java.math.BigDecimal;

public interface OrderRepository {

    String createPendingOrder(String customerId, String shippingAddress, BigDecimal totalAmount);

    void attachPaymentTransaction(String orderId, String paymentTransactionId);

    void markPaid(String orderId, BigDecimal confirmedAmount);

    void attachShippingTask(String orderId, String shippingTaskId);
}
