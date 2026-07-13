package com.example.cutoffdup;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class CutoffDupController {

    private final CutoffStepService stepService;
    private final CutoffHelperService helperService;

    public CutoffDupController(CutoffStepService stepService, CutoffHelperService helperService) {
        this.stepService = stepService;
        this.helperService = helperService;
    }

    public void entry() {
        stepService.step();
        helperService.assist();
    }
}
