package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.evidence.CodeEvidenceSnapshot;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.ToolCallRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 純渲染器:把集中記錄的 tool 呼叫整理成 Markdown 摘要 */
@Slf4j
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

    public static String renderEvidence(CodeEvidenceSnapshot snapshot) {
        if (!snapshot.requiresCode()) {
            return "";
        }
        return """

                ---
                **🔎 Code evidence**

                - status: `%s`
                - reason: `%s`
                """.formatted(snapshot.outcome(), safeInline(snapshot.reasonCode()));
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
                    .map(arguments -> "[find_call_graph] repo: `%s`"
                            .formatted(stringOr("unknown-repo", arguments.get("repoId"))))
                    .orElse("[find_call_graph]");
            case ToolNames.FIND_API_CALL_GRAPH -> parse(argsJson, objectMapper)
                    .map(arguments -> "[find_api_call_graph] %s %s | repo: `%s`".formatted(
                            safeInline(arguments.get("httpMethod")),
                            safeInline(arguments.get("apiPath")),
                            safeInline(arguments.get("repoId"))))
                    .orElse("[find_api_call_graph]");
            default -> "[%s]".formatted(toolName);
        };
    }

    private static String safeInline(Object value) {
        return Objects.toString(value, "")
                .replace('`', '\'')
                .replaceAll("\\R", " ");
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> parse(String argsJson, ObjectMapper objectMapper) {
        try {
            String safeArgs = StringUtils.hasText(argsJson) ? argsJson : "{}";
            return Optional.of(objectMapper.readValue(safeArgs, Map.class));
        } catch (Exception exception) {
            log.debug("Tool call arguments are not valid JSON, rendering without details: {}",
                    argsJson, exception);
            return Optional.empty();
        }
    }

    private static String stringOr(String fallback, Object value) {
        return Objects.toString(value, fallback);
    }
}
