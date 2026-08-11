package com.example.payments;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/payment-options")
public final class PaymentOptionController {

    private final PaymentOptionService paymentOptionService;

    public PaymentOptionController(PaymentOptionService paymentOptionService) {
        this.paymentOptionService = Objects.requireNonNull(paymentOptionService, "payment option service must not be null");
    }

    @PostMapping("/quotes")
    public Map<PaymentMethod, FeeQuote> quoteSupportedOptions(PaymentRequest request) {
        return paymentOptionService.quoteSupportedOptions(request);
    }
}
