package com.example.payments;

import java.util.Map;

public interface PaymentOptionService {

    Map<PaymentMethod, FeeQuote> quoteSupportedOptions(PaymentRequest request);
}
