package com.example.recordenum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PricingController {

    @GetMapping("/pricing/{amount}")
    public long quote(@PathVariable long amount) {
        OrderPricing pricing = new OrderPricing(amount);
        OrderStatus status = OrderStatus.PAID;
        if (status.isFinal()) {
            return pricing.total();
        }
        return 0L;
    }
}
