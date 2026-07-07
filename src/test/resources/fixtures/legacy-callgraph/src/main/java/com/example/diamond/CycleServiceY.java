package com.example.diamond;

import org.springframework.stereotype.Service;

@Service
public class CycleServiceY {

    private final CycleServiceX cycleServiceX;

    public CycleServiceY(CycleServiceX cycleServiceX) {
        this.cycleServiceX = cycleServiceX;
    }

    public String pong() {
        return cycleServiceX.ping();
    }
}
