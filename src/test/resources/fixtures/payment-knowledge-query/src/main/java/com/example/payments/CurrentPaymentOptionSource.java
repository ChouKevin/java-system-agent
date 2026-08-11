package com.example.payments;

import java.util.Map;

public interface CurrentPaymentOptionSource {

    Map<PaymentMethod, FeeQuote> loadCurrentOptions();
}
