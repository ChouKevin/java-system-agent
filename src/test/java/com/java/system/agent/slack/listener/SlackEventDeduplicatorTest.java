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
    void tryBegin_should_claim_new_event_and_reject_duplicate() {
        assertTrue(deduplicator.tryBegin("event-123"));
        assertFalse(deduplicator.tryBegin("event-123"));
        assertTrue(deduplicator.tryBegin("event-456"));
    }

    @Test
    void abandon_should_allow_retry_after_failure() {
        assertTrue(deduplicator.tryBegin("event-123"));
        deduplicator.abandon("event-123");

        assertTrue(deduplicator.tryBegin("event-123"));
    }

    @Test
    void testCleanup() {
        deduplicator.tryBegin("event-1");
        deduplicator.tryBegin("event-2");
        
        assertEquals(2, deduplicator.size());
        
        // Cleanup with no expired items
        deduplicator.cleanup();
        assertEquals(2, deduplicator.size());
        
        // Clear manual
        deduplicator.clear();
        assertEquals(0, deduplicator.size());
    }
    
    @Test
    void null_event_id_should_always_process_and_never_track() {
        assertTrue(deduplicator.tryBegin(null));
        assertTrue(deduplicator.tryBegin(null));
        assertEquals(0, deduplicator.size());
    }
}
