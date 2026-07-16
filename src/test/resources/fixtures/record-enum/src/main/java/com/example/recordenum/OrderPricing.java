package com.example.recordenum;

public record OrderPricing(long amount) {

    public long total() {
        return amount + tax();
    }

    private long tax() {
        return amount / 10;
    }

    public long applyRule(PricingRule rule) {
        return rule.apply(total());
    }
}
