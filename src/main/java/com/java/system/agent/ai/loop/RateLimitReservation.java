package com.java.system.agent.ai.loop;

public record RateLimitReservation(int reservedTokens) {

    public static RateLimitReservation none() {
        return new RateLimitReservation(0);
    }
}
