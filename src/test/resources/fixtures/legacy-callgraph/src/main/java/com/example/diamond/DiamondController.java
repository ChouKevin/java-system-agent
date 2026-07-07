package com.example.diamond;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DiamondController {

    private final DiamondServiceA serviceA;
    private final DiamondServiceB serviceB;

    public DiamondController(DiamondServiceA serviceA, DiamondServiceB serviceB) {
        this.serviceA = serviceA;
        this.serviceB = serviceB;
    }

    @GetMapping("/diamond")
    public String entry() {
        return serviceA.left() + serviceB.right();
    }
}
