package com.java.system.agent.ratelimit;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class RateLimitingService {

    private final ConcurrentHashMap<String, Instant> lastRequestMap = new ConcurrentHashMap<>();
    private static final long COOLDOWN_SECONDS = 5;

    public synchronized boolean tryAcquire(String userId) {
        Instant now = Instant.now();
        Instant lastRequest = lastRequestMap.get(userId);

        if (lastRequest != null && now.isBefore(lastRequest.plusSeconds(COOLDOWN_SECONDS))) {
            return false;
        }

        lastRequestMap.put(userId, now);
        return true;
    }

    /** 定期清理超過 1 小時的記錄。 */
    @Scheduled(fixedDelay = 3600000)
    public void cleanup() {
        Instant threshold = Instant.now().minusSeconds(3600);
        int initialSize = lastRequestMap.size();
        lastRequestMap.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
        int finalSize = lastRequestMap.size();
        log.info("RateLimitingService cleanup: removed {} old records. Current size: {}", (initialSize - finalSize),
                finalSize);
    }

    public long getCooldownSeconds() {
        return COOLDOWN_SECONDS;
    }
}
