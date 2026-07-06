package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.ToolCallRecord;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 純渲染器:把集中記錄的 tool 呼叫整理成 Markdown 摘要 */
public final class ToolCallSummary {

    private ToolCallSummary() {
    }

    public static String render(List<ToolCallRecord> calls, ObjectMapper objectMapper,
                                Set<String> toolNames, String title) {
        StringBuilder summary = new StringBuilder("\n\n---\n**" + title + "**\n\n");
        int index = 1;
        for (ToolCallRecord call : calls) {
            if (!toolNames.contains(call.name())) {
                continue;
            }
            String line = formatEntry(call.name(), call.arguments(), objectMapper);
            if (StringUtils.hasText(line)) {
                summary.append(index++).append(". ").append(line).append("\n");
            }
        }
        return index == 1 ? "" : summary.toString();
    }

    /** 攤平整棵 trace tree 的 tool 呼叫，供 debug/tree view 使用 */
    public static List<ToolCallRecord> flatten(LoopTrace trace) {
        List<ToolCallRecord> calls = new ArrayList<>(trace.toolCalls());
        for (LoopStep step : trace.steps()) {
            for (LoopTrace childTrace : step.childTraces()) {
                calls.addAll(flatten(childTrace));
            }
        }
        return calls;
    }

    private static String formatEntry(String toolName, String argsJson, ObjectMapper objectMapper) {
        return switch (toolName) {
            case ToolNames.READ_SERVICE_MAP -> "[read_service_map]";
            case ToolNames.READ_BUSINESS_MAP -> parse(argsJson, objectMapper)
                    .map(arguments -> "[read_business_map] repo: `%s`".formatted(arguments.get("repoId")))
                    .orElse("[read_business_map]");
            case ToolNames.READ_BUSINESS_GROUP_DOC -> parse(argsJson, objectMapper)
                    .map(arguments -> "[read_business_group_doc] repo: `%s` | group: `%s`"
                            .formatted(arguments.get("repoId"), arguments.get("groupName")))
                    .orElse("[read_business_group_doc]");
            case ToolNames.FIND_CALL_GRAPH -> parse(argsJson, objectMapper)
                    .map(arguments -> "[find_call_graph] repo: `%s` | class: `%s` | method: `%s`"
                            .formatted(
                                    stringOr("unknown-repo", arguments.get("repoId")),
                                    stringOr("unknown-class", arguments.get("className")),
                                    stringOr("unknown-method", arguments.get("methodSignature"))))
                    .orElse("[find_call_graph]");
            default -> "[%s]".formatted(toolName);
        };
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> parse(String argsJson, ObjectMapper objectMapper) {
        try {
            String safeArgs = StringUtils.hasText(argsJson) ? argsJson : "{}";
            return Optional.of(objectMapper.readValue(safeArgs, Map.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String stringOr(String fallback, Object value) {
        return Objects.toString(value, fallback);
    }
}
