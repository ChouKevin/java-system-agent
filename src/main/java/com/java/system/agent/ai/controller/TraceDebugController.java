package com.java.system.agent.ai.controller;

import com.java.system.agent.ai.trace.AgentTraceRecord;
import com.java.system.agent.ai.trace.AgentTraceStore;
import com.java.system.agent.ai.trace.AgentTraceSummary;
import com.java.system.agent.ai.trace.TraceSearchQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** 開發用 trace 查詢端點 */
@RestController
@RequestMapping("/debug/trace")
@Profile("dev|uat")
class TraceDebugController {

    private final AgentTraceStore traceStore;

    TraceDebugController(AgentTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @GetMapping
    List<AgentTraceSummary> search(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) Boolean accepted,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") int limit) {
        TraceSearchQuery query = TraceSearchQuery.of(
                userId, eventId, accepted, from, to, limit);
        return readTraceStore(() -> traceStore.search(query));
    }

    @GetMapping("/conversations/{threadTs}")
    List<AgentTraceRecord> conversation(@PathVariable String threadTs) {
        return readTraceStore(() -> traceStore.conversation(threadTs));
    }

    @GetMapping("/{conversationId}")
    List<AgentTraceRecord> recent(@PathVariable String conversationId) {
        return readTraceStore(() -> traceStore.recent(conversationId));
    }

    @GetMapping("/id/{traceId}")
    ResponseEntity<AgentTraceRecord> byTraceId(@PathVariable String traceId) {
        return readTraceStore(() -> traceStore.byTraceId(traceId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private <T> T readTraceStore(Supplier<T> readOperation) {
        try {
            return readOperation.get();
        } catch (RuntimeException exception) {
            throw new TraceStoreReadException("Trace store read failed", exception);
        }
    }

    private static final class TraceStoreReadException extends RuntimeException {

        private TraceStoreReadException(String message, RuntimeException cause) {
            super(message, cause);
        }
    }
}
