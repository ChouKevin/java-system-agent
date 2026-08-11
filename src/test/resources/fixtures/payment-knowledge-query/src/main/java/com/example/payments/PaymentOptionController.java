package com.example.payments;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/payment-options")
public final class PaymentOptionController {

    private final PaymentOptionService paymentOptionService;
    private final CurrentPaymentOptionSource currentPaymentOptionSource;

    public PaymentOptionController(
            PaymentOptionService paymentOptionService,
            CurrentPaymentOptionSource currentPaymentOptionSource) {
        this.paymentOptionService = Objects.requireNonNull(
                paymentOptionService, "payment option service must not be null");
        this.currentPaymentOptionSource = Objects.requireNonNull(
                currentPaymentOptionSource, "current payment option source must not be null");
    }

    @PostMapping("/quotes")
    public Map<PaymentMethod, FeeQuote> quoteSupportedOptions(PaymentRequest request) {
        return paymentOptionService.quoteSupportedOptions(request);
    }

    @GetMapping("/current")
    public Map<PaymentMethod, FeeQuote> currentOptions() {
        return currentPaymentOptionSource.loadCurrentOptions();
    }
}
