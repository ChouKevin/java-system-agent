package com.example.diamond;

import org.springframework.stereotype.Service;

@Service
public class CycleServiceX {

    private final CycleServiceY cycleServiceY;

    public CycleServiceX(CycleServiceY cycleServiceY) {
        this.cycleServiceY = cycleServiceY;
    }

    public String ping() {
        return cycleServiceY.pong();
    }
}
