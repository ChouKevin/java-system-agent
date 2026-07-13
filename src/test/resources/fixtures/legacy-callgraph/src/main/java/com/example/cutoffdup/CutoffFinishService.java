package com.example.cutoffdup;

import org.springframework.stereotype.Service;

@Service
public class CutoffFinishService {
    private final CutoffHelperService helperService;

    public CutoffFinishService(CutoffHelperService helperService) {
        this.helperService = helperService;
    }

    public void finish() {
        helperService.assist();
    }
}
