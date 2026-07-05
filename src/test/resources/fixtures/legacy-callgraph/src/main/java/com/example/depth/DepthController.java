package com.example.depth;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class DepthController {

    private final DepthServiceA serviceA;

    public DepthController(DepthServiceA serviceA) {
        this.serviceA = serviceA;
    }

    public void entry() {
        serviceA.processA();
    }
}
