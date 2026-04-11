package com.example.depth;

import org.springframework.stereotype.Service;

@Service
public class DepthServiceC {

    private final DepthServiceD serviceD;

    public DepthServiceC(DepthServiceD serviceD) {
        this.serviceD = serviceD;
    }

    public void processC() {
        serviceD.processD();
    }
}
