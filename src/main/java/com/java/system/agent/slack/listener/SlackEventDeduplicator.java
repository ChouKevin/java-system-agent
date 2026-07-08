package com.java.system.agent.slack.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Slack event 去重服務 */
@Service
@Slf4j
public class SlackEventDeduplicator {

    private final Map<String, Instant> processedEvents = new ConcurrentHashMap<>();
    private static final long EXPIRATION_HOURS = 1;

    /** 嘗試取得事件處理權:新事件回 true 並開始追蹤;重複事件回 false */
    public boolean tryBegin(String eventId) {
        if (eventId == null) {
            return true;
        }

        boolean claimed = processedEvents.putIfAbsent(eventId, Instant.now()) == null;
        log.debug("Event ID {} {}", eventId, claimed ? "claimed" : "is a duplicate");
        return claimed;
    }

    /** 處理失敗時釋放事件，讓 Slack 重送可以重試 */
    public void abandon(String eventId) {
        if (eventId != null) {
            processedEvents.remove(eventId);
        }
    }

    /** 定期清理過期事件 */
    @Scheduled(fixedRate = 600000) // Run every 10 minutes
    public void cleanup() {
        Instant threshold = Instant.now().minusSeconds(EXPIRATION_HOURS * 3600);
        int initialSize = processedEvents.size();
        
        processedEvents.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
        
        int finalSize = processedEvents.size();
        int removed = initialSize - finalSize;
        
        if (removed > 0) {
            log.info("Cleaned up {} expired Slack event IDs from deduplicator.", removed);
        }
    }
    
    public void clear() {
        processedEvents.clear();
    }
    
    public int size() {
        return processedEvents.size();
    }
}
