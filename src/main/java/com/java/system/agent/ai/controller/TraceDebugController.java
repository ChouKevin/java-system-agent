package com.java.system.agent.ai.controller;

import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.trace.LoopTraceStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 開發用 trace 查詢端點 */
@RestController
@RequestMapping("/debug/trace")
@Profile("dev|uat")
class TraceDebugController {

    private final LoopTraceStore traceStore;

    TraceDebugController(LoopTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @GetMapping("/{conversationId}")
    List<LoopTrace> recent(@PathVariable String conversationId) {
        return traceStore.recent(conversationId);
    }

    @GetMapping("/id/{traceId}")
    ResponseEntity<LoopTrace> byTraceId(@PathVariable String traceId) {
        return traceStore.byTraceId(traceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
