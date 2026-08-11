package com.example.payments;

public interface PaymentFeePolicy {

    FeeQuote quote(PaymentRequest request);
}
