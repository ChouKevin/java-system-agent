package com.java.system.agent.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 記錄 M2 整合測試跨越 HTTP 與 LLM 邊界的呼叫順序
 */
public final class CallTimeline {

    public static final String HTTP_REPOSITORY_CATALOG = "HTTP repository catalog";
    public static final String LLM_QUERY_ACTION = "LLM query action";
    public static final String HTTP_REPOSITORY_REVISION = "HTTP repository revision";
    public static final String HTTP_LIST_ENTRY_POINTS = "HTTP list-entry-points capability";
    public static final String LLM_ANSWER_ACTION = "LLM answer action";
    public static final String LLM_VERIFIER = "LLM verifier";

    private final List<String> calls = new ArrayList<>();

    public synchronized void record(String call) {
        Objects.requireNonNull(call, "timeline call must not be null");
        if (call.isBlank()) {
            throw new IllegalArgumentException("timeline call must not be blank");
        }
        calls.add(call);
    }

    public synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    public synchronized void clear() {
        calls.clear();
    }
}
