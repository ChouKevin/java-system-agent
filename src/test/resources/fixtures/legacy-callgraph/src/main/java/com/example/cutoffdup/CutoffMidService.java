package com.example.cutoffdup;

import org.springframework.stereotype.Service;

@Service
public class CutoffMidService {
    private final CutoffFinishService finishService;

    public CutoffMidService(CutoffFinishService finishService) {
        this.finishService = finishService;
    }

    public void mid() {
        finishService.finish();
    }
}
