package com.java.system.agent.slack.listener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlackEventDeduplicatorTest {

    private SlackEventDeduplicator deduplicator;

    @BeforeEach
    void setUp() {
        deduplicator = new SlackEventDeduplicator();
    }

    @Test
    void testIsDuplicate() {
        String eventId = "event-123";
        
        // First time should be new
        assertFalse(deduplicator.isDuplicate(eventId), "First call should not be a duplicate");
        
        // Second time should be duplicate
        assertTrue(deduplicator.isDuplicate(eventId), "Second call with same ID should be a duplicate");
        
        // Different ID should be new
        assertFalse(deduplicator.isDuplicate("event-456"), "Different ID should not be a duplicate");
    }

    @Test
    void testCleanup() {
        deduplicator.isDuplicate("event-1");
        deduplicator.isDuplicate("event-2");
        
        assertEquals(2, deduplicator.size());
        
        // Cleanup with no expired items
        deduplicator.cleanup();
        assertEquals(2, deduplicator.size());
        
        // Clear manual
        deduplicator.clear();
        assertEquals(0, deduplicator.size());
    }
    
    @Test
    void testNullEventId() {
        assertFalse(deduplicator.isDuplicate(null), "Null event ID should not be considered duplicate or tracked");
        assertEquals(0, deduplicator.size());
    }
}
