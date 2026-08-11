package com.example.payments;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DefaultPaymentOptionService implements PaymentOptionService {

    private final Map<PaymentMethod, PaymentFeePolicy> policies;

    public DefaultPaymentOptionService() {
        CreditCardFeePolicy creditCardPolicy = new CreditCardFeePolicy();
        policies = Map.of(
                PaymentMethod.CREDIT_CARD, creditCardPolicy,
                PaymentMethod.BANK_TRANSFER, new BankTransferFeePolicy(),
                PaymentMethod.CONVENIENCE_STORE, new ConvenienceStoreFeePolicy(),
                PaymentMethod.APPLE_PAY, new ApplePayFeePolicy(creditCardPolicy));
    }

    @Override
    public Map<PaymentMethod, FeeQuote> quoteSupportedOptions(PaymentRequest request) {
        Objects.requireNonNull(request, "payment request must not be null");
        Map<PaymentMethod, FeeQuote> quotes = new LinkedHashMap<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            PaymentFeePolicy policy = policies.get(method);
            quotes.put(method, Objects.requireNonNull(policy, "payment policy must not be null").quote(request));
        }
        return Map.copyOf(quotes);
    }
}
