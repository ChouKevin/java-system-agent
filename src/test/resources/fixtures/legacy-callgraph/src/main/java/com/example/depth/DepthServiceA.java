package com.example.depth;

import org.springframework.stereotype.Service;

@Service
public class DepthServiceA {

    private final DepthServiceB serviceB;

    public DepthServiceA(DepthServiceB serviceB) {
        this.serviceB = serviceB;
    }

    public void processA() {
        serviceB.processB();
    }
}
