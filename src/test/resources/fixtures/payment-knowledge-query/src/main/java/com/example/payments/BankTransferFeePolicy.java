package com.example.payments;

public final class BankTransferFeePolicy implements PaymentFeePolicy {

    @Override
    public FeeQuote quote(PaymentRequest request) {
        return FeeQuote.free("bank transfer has no processing fee");
    }
}
