package com.java.system.agent.ai.loop;

import java.util.ArrayList;
import java.util.List;

/** loop 執行中的不可變狀態 */
public record LoopState(
        LoopRequest request,
        int iteration,
        List<String> critiques,
        List<LoopStep> history,
        long startedAtMillis) {

    public LoopState {
        critiques = List.copyOf(critiques);
        history = List.copyOf(history);
    }

    public static LoopState init(LoopRequest request) {
        return new LoopState(request, 0, List.of(), List.of(), System.currentTimeMillis());
    }

    public String query() {
        return request.userQuery();
    }

    public LoopState recordStep(LoopStep step) {
        List<LoopStep> nextHistory = new ArrayList<>(history);
        nextHistory.add(step);
        return new LoopState(request, iteration + 1, critiques, nextHistory, startedAtMillis);
    }

    public LoopState injectCritique(String critique) {
        List<String> nextCritiques = new ArrayList<>(critiques);
        nextCritiques.add(critique);
        return new LoopState(request, iteration, nextCritiques, history, startedAtMillis);
    }
}
