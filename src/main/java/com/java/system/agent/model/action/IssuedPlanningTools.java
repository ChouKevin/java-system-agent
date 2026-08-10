package com.java.system.agent.model.action;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;

/**
 * 同步保存目前已配發 planning tool 名稱與 callback 的不可變模型層快照
 */
record IssuedPlanningTools(List<String> names, List<ToolCallback> callbacks) {

    IssuedPlanningTools {
        Objects.requireNonNull(names, "issued planning tool names must not be null");
        Objects.requireNonNull(callbacks, "issued planning tool callbacks must not be null");
        names = List.copyOf(names);
        callbacks = List.copyOf(callbacks);
        if (names.size() != callbacks.size()) {
            throw new IllegalArgumentException("issued planning tool names and callbacks must have equal sizes");
        }
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            ToolCallback callback = callbacks.get(index);
            String callbackName = callback.getToolDefinition().name();
            if (!name.equals(callbackName)) {
                throw new IllegalArgumentException("issued planning tool name must match callback definition");
            }
        }
    }
}
