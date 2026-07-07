package com.example.diamond;

import org.springframework.stereotype.Service;

@Service
public class DiamondServiceB {

    private final SharedLeafService shared;

    public DiamondServiceB(SharedLeafService shared) {
        this.shared = shared;
    }

    public String right() {
        return shared.work();
    }
}
