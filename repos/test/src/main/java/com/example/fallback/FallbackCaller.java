package com.example.fallback;

public class FallbackCaller {

    private final FallbackWork fallbackWork = new FallbackWorkImpl();

    public void entryPointFallback() {
        fallbackWork.doWork();
    }
}
