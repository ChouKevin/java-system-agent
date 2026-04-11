package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-call recorder that formats tool calls into a Markdown summary.
 *
 */
@Slf4j
public class RequestScopedToolCallRecorder implements ToolCallRecorder {

    private final ObjectMapper objectMapper;
    private final List<ToolCallEntry> entries = new ArrayList<>();

    public RequestScopedToolCallRecorder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(String toolName, String argumentsJson) {
        String safeArgs = StringUtils.hasText(argumentsJson) ? argumentsJson : "{}";
        entries.add(new ToolCallEntry(toolName, safeArgs));
        log.debug("ToolCall: {} {}", toolName, safeArgs);
    }

    @Override
    public String getSummaryForTools(Set<String> toolNames, String title) {
        if (entries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n---\n**" + title + "**\n\n");
        int index = 1;
        for (ToolCallEntry entry : entries) {
            if (!toolNames.contains(entry.toolName())) continue;
            String line = formatEntry(entry.toolName(), entry.argumentsJson());
            if (!StringUtils.hasText(line)) continue;
            sb.append(index++).append(". ").append(line).append("\n");
        }
        return index == 1 ? "" : sb.toString();
    }

    private String formatEntry(String toolName, String argsJson) {
        return switch (toolName) {
            case ToolNames.READ_SERVICE_MAP -> "[read_service_map]";
            case ToolNames.READ_BUSINESS_MAP -> formatReadBusinessMap(argsJson);
            case ToolNames.READ_SKILL_DOC -> formatReadSkillDoc(argsJson);
            case ToolNames.FIND_CALL_GRAPH -> formatFindCallGraph(argsJson);
            default -> "[%s]".formatted(toolName);
        };
    }

    private String formatReadBusinessMap(String argsJson) {
        return parseArgs(argsJson)
                .map(args -> "[read_business_map] repo: `%s`".formatted(args.get("repoId")))
                .orElse("[read_business_map]");
    }

    private String formatReadSkillDoc(String argsJson) {
        return parseArgs(argsJson)
                .map(args -> "[read_skill_doc] repo: `%s` | group: `%s`"
                        .formatted(args.get("repoId"), args.get("groupName")))
                .orElse("[read_skill_doc]");
    }

    private String formatFindCallGraph(String argsJson) {
        return parseArgs(argsJson)
                .map(args -> {
                    String repoName = stringOr("unknown-repo", args.get("repoId"));
                    String className = stringOr("unknown-class", args.get("className"));
                    String methodName = stringOr("unknown-method", args.get("methodSignature"));
                    return "[find_call_graph] repo: `%s` | class: `%s` | method: `%s`"
                            .formatted(repoName, className, methodName);
                })
                .orElse("[find_call_graph]");
    }

    private Optional<Map<String, Object>> parseArgs(String argsJson) {
        try {
            Map<String, Object> map = objectMapper.readValue(argsJson, Map.class);
            return Optional.of(map);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String stringOr(String fallback, Object value) {
        return value != null ? value.toString() : fallback;
    }

    private record ToolCallEntry(String toolName, String argumentsJson) {
    }
}
