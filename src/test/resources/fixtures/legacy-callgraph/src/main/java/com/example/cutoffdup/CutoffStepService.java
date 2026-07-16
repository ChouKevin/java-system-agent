package com.example.cutoffdup;

import org.springframework.stereotype.Service;

@Service
public class CutoffStepService {
    private final CutoffMidService midService;

    public CutoffStepService(CutoffMidService midService) {
        this.midService = midService;
    }

    public void step() {
        midService.mid();
    }
}
