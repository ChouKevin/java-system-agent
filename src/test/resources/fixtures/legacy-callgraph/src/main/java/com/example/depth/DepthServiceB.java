package com.example.depth;

import org.springframework.stereotype.Service;

@Service
public class DepthServiceB {

    private final DepthServiceC serviceC;

    public DepthServiceB(DepthServiceC serviceC) {
        this.serviceC = serviceC;
    }

    public void processB() {
        serviceC.processC();
    }
}
