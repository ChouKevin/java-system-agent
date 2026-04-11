package com.example.depth;

import org.springframework.stereotype.Service;

@Service
public class DepthServiceD {

    private final DepthServiceE serviceE;

    public DepthServiceD(DepthServiceE serviceE) {
        this.serviceE = serviceE;
    }

    public void processD() {
        serviceE.processE();
    }
}
