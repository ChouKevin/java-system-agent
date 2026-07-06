package com.java.system.agent.ai.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 請求範圍的子 trace 收集器 */
public final class LoopTraceCollector {

    private final List<LoopTrace> pending = new ArrayList<>();

    public synchronized void add(LoopTrace trace) {
        pending.add(Objects.requireNonNull(trace, "trace must not be null"));
    }

    /** 取出目前累積的子 trace 並清空 */
    public synchronized List<LoopTrace> drain() {
        List<LoopTrace> traces = List.copyOf(pending);
        pending.clear();
        return traces;
    }
}
