package com.example.diamond;

import org.springframework.stereotype.Service;

@Service
public class DiamondServiceA {

    private final SharedLeafService shared;

    public DiamondServiceA(SharedLeafService shared) {
        this.shared = shared;
    }

    public String left() {
        return shared.work();
    }
}
