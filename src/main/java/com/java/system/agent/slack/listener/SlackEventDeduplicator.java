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

    /** 檢查是否重複，若為新事件則追蹤 */
    public boolean isDuplicate(String eventId) {
        if (eventId == null) {
            return false;
        }
        
        // putIfAbsent returns null if the key was not present
        Instant existing = processedEvents.putIfAbsent(eventId, Instant.now());
        boolean isDuplicate = existing != null;
        
        if (isDuplicate) {
            log.debug("Event ID {} is a duplicate.", eventId);
        } else {
            log.debug("Tracking new event ID {}.", eventId);
        }
        
        return isDuplicate;
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
