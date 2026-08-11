package com.example.payments;

import java.math.BigDecimal;
import java.util.Objects;

public record PaymentRequest(BigDecimal amount, MembershipTier membershipTier) {

    public PaymentRequest {
        amount = Objects.requireNonNull(amount, "amount must not be null");
        membershipTier = Objects.requireNonNull(membershipTier, "membership tier must not be null");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
    }
}
