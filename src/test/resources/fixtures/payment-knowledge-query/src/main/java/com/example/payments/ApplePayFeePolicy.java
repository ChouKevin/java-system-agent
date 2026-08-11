package com.example.payments;

import java.util.Objects;

public final class ApplePayFeePolicy implements PaymentFeePolicy {

    private final CreditCardFeePolicy cardPolicy;

    public ApplePayFeePolicy(CreditCardFeePolicy cardPolicy) {
        this.cardPolicy = Objects.requireNonNull(cardPolicy, "card policy must not be null");
    }

    @Override
    public FeeQuote quote(PaymentRequest request) {
        return cardPolicy.quote(request);
    }
}
