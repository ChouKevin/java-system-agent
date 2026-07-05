package com.example.fallback;

import org.springframework.stereotype.Service;

@Service
public class FallbackWorkImpl implements FallbackWork {

    @Override
    public void doWork() {
        System.out.println("fallback");
    }
}
