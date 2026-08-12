package com.java.system.agent.model.action;

import org.springframework.ai.tool.ToolCallback;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 同步保存目前已配發 planning tool candidate authority 與 callback 的不可變模型層快照
 */
record IssuedPlanningTools(Map<String, List<String>> candidateAuthority, List<ToolCallback> callbacks) {

    IssuedPlanningTools {
        Objects.requireNonNull(candidateAuthority, "issued planning tool candidate authority must not be null");
        Objects.requireNonNull(callbacks, "issued planning tool callbacks must not be null");
        candidateAuthority = copyCandidateAuthority(candidateAuthority);
        callbacks = List.copyOf(callbacks);
        if (candidateAuthority.size() != callbacks.size()) {
            throw new IllegalArgumentException("issued planning tool candidate authority and callbacks must have equal sizes");
        }
        List<String> names = List.copyOf(candidateAuthority.keySet());
        for (int index = 0; index < callbacks.size(); index++) {
            String name = names.get(index);
            ToolCallback callback = Objects.requireNonNull(callbacks.get(index),
                    "issued planning tool callback must not be null");
            String callbackName = Objects.requireNonNull(callback.getToolDefinition(),
                    "issued planning tool callback definition must not be null").name();
            if (!name.equals(callbackName)) {
                throw new IllegalArgumentException("issued planning tool name must match callback definition");
            }
        }
    }

    List<String> names() {
        return List.copyOf(candidateAuthority.keySet());
    }

    private static Map<String, List<String>> copyCandidateAuthority(Map<String, List<String>> candidateAuthority) {
        Map<String, List<String>> copiedAuthority = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : candidateAuthority.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "issued planning tool name must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("issued planning tool name must not be blank");
            }
            List<String> handles = Objects.requireNonNull(entry.getValue(),
                    "issued planning tool candidate handles must not be null");
            List<String> copiedHandles = handles.stream()
                    .map(handle -> Objects.requireNonNull(handle,
                            "issued planning tool candidate handle must not be null"))
                    .toList();
            copiedAuthority.put(name, copiedHandles);
        }
        return Collections.unmodifiableMap(copiedAuthority);
    }
}
