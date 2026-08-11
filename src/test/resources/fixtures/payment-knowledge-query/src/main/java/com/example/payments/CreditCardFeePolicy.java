package com.example.payments;

import java.math.BigDecimal;

public final class CreditCardFeePolicy implements PaymentFeePolicy {

    private static final BigDecimal RATE = new BigDecimal("0.025");

    @Override
    public FeeQuote quote(PaymentRequest request) {
        if (request.membershipTier() == MembershipTier.PREMIUM) {
            return FeeQuote.free("premium member exemption");
        }
        return FeeQuote.percentage(request.amount().multiply(RATE), RATE);
    }
}
