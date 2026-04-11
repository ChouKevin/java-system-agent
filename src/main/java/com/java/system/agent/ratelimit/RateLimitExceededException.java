package com.java.system.agent.ratelimit;

import lombok.Getter;

/**
 * Exception thrown when the rate limit for a user or key is exceeded.
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final String key;

    public RateLimitExceededException(String key, String message) {
        super(message);
        this.key = key;
    }
}
