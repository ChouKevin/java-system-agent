package com.example.payments;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record FeeQuote(BigDecimal fee, Optional<BigDecimal> rate, String description) {

    public FeeQuote {
        fee = Objects.requireNonNull(fee, "fee must not be null");
        rate = Objects.requireNonNull(rate, "rate must not be null");
        description = Objects.requireNonNull(description, "description must not be null");
    }

    public static FeeQuote free(String description) {
        return new FeeQuote(BigDecimal.ZERO, Optional.empty(), description);
    }

    public static FeeQuote percentage(BigDecimal fee, BigDecimal rate) {
        return new FeeQuote(fee, Optional.of(rate), "percentage processing fee");
    }

    public static FeeQuote fixed(BigDecimal fee) {
        return new FeeQuote(fee, Optional.empty(), "fixed convenience store processing fee");
    }
}
