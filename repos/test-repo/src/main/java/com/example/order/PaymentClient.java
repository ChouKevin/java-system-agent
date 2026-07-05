package com.example.order;

import java.math.BigDecimal;

public interface PaymentClient {

    String authorize(String orderId, String paymentMethodId, BigDecimal totalAmount);

    PaymentResult confirm(String orderId, String paymentTransactionId);
}

record PaymentResult(String paymentTransactionId, BigDecimal confirmedAmount) {
}
