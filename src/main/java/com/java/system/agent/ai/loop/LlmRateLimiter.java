package com.java.system.agent.ai.loop;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

public interface LlmRateLimiter {

    LlmRateLimiter NOOP = new LlmRateLimiter() {
        @Override
        public RateLimitReservation acquire(Prompt prompt) {
            return RateLimitReservation.none();
        }

        @Override
        public RateLimitReservation acquire(String promptText) {
            return RateLimitReservation.none();
        }

        @Override
        public void record(RateLimitReservation reservation, ChatResponse response) {
        }

        @Override
        public void record(RateLimitReservation reservation, String responseText) {
        }
    };

    RateLimitReservation acquire(Prompt prompt);

    RateLimitReservation acquire(String promptText);

    void record(RateLimitReservation reservation, ChatResponse response);

    void record(RateLimitReservation reservation, String responseText);
}
