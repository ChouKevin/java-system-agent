package com.example.payments;

import java.math.BigDecimal;

public final class ConvenienceStoreFeePolicy implements PaymentFeePolicy {

    private static final BigDecimal FIXED_FEE = new BigDecimal("30");

    @Override
    public FeeQuote quote(PaymentRequest request) {
        return FeeQuote.fixed(FIXED_FEE);
    }
}
