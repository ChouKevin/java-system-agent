package com.java.system.agent.model.action;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;

/**
 * 同步保存目前已配發 planning tool 名稱與 callback 的不可變模型層快照。
 */
record IssuedPlanningTools(List<String> names, List<ToolCallback> callbacks) {

    IssuedPlanningTools {
        Objects.requireNonNull(names, "issued planning tool names must not be null");
        Objects.requireNonNull(callbacks, "issued planning tool callbacks must not be null");
        names = copyNames(names);
        callbacks = List.copyOf(callbacks);
        if (names.size() != callbacks.size()) {
            throw new IllegalArgumentException("issued planning tool names and callbacks must have equal sizes");
        }
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

    private static List<String> copyNames(List<String> names) {
        List<String> copiedNames = names.stream()
                .map(name -> Objects.requireNonNull(name, "issued planning tool name must not be null"))
                .toList();
        if (copiedNames.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("issued planning tool name must not be blank");
        }
        return copiedNames;
    }

}
