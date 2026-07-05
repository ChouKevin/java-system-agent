package com.example.visit;

public class CollisionCaller {

    private final AlphaService alphaService = new AlphaService();
    private final BetaService betaService = new BetaService();

    public void entry() {
        alphaService.process();
        betaService.process();
    }
}
