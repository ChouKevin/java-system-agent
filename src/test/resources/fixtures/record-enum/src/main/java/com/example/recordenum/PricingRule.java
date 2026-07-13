package com.example.recordenum;

import org.springframework.stereotype.Component;

@Component
public class PricingRule {

    public long apply(long amount) {
        return amount * 2;
    }
}
