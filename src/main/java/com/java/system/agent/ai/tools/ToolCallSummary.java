package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.evidence.CodeEvidenceSnapshot;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.ToolCallRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 純渲染器:把集中記錄的 tool 呼叫整理成 Markdown 摘要 */
@Slf4j
public final class ToolCallSummary {

    private static final Set<String> SUPPORTED_HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");
    private static final Pattern METHOD_PREFIXED_PATH = Pattern.compile(
            "(?i)^\\[?([!#$%&'*+.^_`|~0-9A-Z-]+)\\]?\\s*:?\\s*"
                    + "(https?://\\S+|/\\S*)$");
    private static final Pattern HTTP_URL = Pattern.compile("(?i)https?://");

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
                """.formatted(snapshot.outcome(), safeSummaryField(snapshot.reasonCode()));
    }

    private static String formatEntry(String toolName, String argsJson, ObjectMapper objectMapper) {
        return switch (toolName) {
            case ToolNames.READ_SERVICE_MAP -> "[read_service_map]";
            case ToolNames.READ_BUSINESS_MAP -> parse(argsJson, objectMapper)
                    .map(arguments -> "[read_business_map] repo: `%s`"
                            .formatted(safeSummaryField(arguments.get("repoId"))))
                    .orElse("[read_business_map]");
            case ToolNames.READ_BUSINESS_GROUP_DOC -> parse(argsJson, objectMapper)
                    .map(arguments -> "[read_business_group_doc] repo: `%s` | group: `%s`"
                            .formatted(
                                    safeSummaryField(arguments.get("repoId")),
                                    safeSummaryField(arguments.get("groupName"))))
                    .orElse("[read_business_group_doc]");
            case ToolNames.FIND_CALL_GRAPH -> parse(argsJson, objectMapper)
                    .map(arguments -> "[find_call_graph] repo: `%s`"
                            .formatted(safeSummaryField(
                                    stringOr("unknown-repo", arguments.get("repoId")))))
                    .orElse("[find_call_graph]");
            case ToolNames.FIND_API_CALL_GRAPH -> parse(argsJson, objectMapper)
                    .map(arguments -> "[find_api_call_graph] %s %s | repo: `%s`".formatted(
                            safeSummaryField(arguments.get("httpMethod")),
                            safeApiPath(arguments.get("apiPath")),
                            safeSummaryField(arguments.get("repoId"))))
                    .orElse("[find_api_call_graph]");
            default -> "[%s]".formatted(toolName);
        };
    }

    private static String safeApiPath(Object value) {
        String rawApiPath = stripMatchingOuterWrapper(Objects.toString(value, "").strip());
        int urlMarkerCount = countHttpUrlMarkers(rawApiPath);
        if (urlMarkerCount > 0) {
            if (urlMarkerCount != 1) {
                return "/";
            }
            return safeApiPathField(extractSafeHttpUrlPath(rawApiPath));
        }
        String apiPath = stripSupportedMethodPrefix(rawApiPath);
        String path = isAbsoluteHttpUrl(apiPath)
                ? extractAbsoluteHttpPath(apiPath)
                : removeQueryAndFragment(apiPath);
        return safeApiPathField(path);
    }

    private static int countHttpUrlMarkers(String value) {
        Matcher matcher = HTTP_URL.matcher(value);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String extractSafeHttpUrlPath(String value) {
        if (isAbsoluteHttpUrl(value)) {
            return extractAbsoluteHttpPath(value);
        }
        Matcher matcher = METHOD_PREFIXED_PATH.matcher(value);
        if (!matcher.matches()) {
            return "/";
        }
        String method = matcher.group(1).toUpperCase(Locale.ROOT);
        if (!SUPPORTED_HTTP_METHODS.contains(method)) {
            return "/";
        }
        return extractAbsoluteHttpPath(matcher.group(2));
    }

    private static String stripMatchingOuterWrapper(String value) {
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if (first == last && (first == '`' || first == '\'' || first == '"')) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    private static String stripSupportedMethodPrefix(String value) {
        Matcher matcher = METHOD_PREFIXED_PATH.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        String method = matcher.group(1).toUpperCase(Locale.ROOT);
        return SUPPORTED_HTTP_METHODS.contains(method) ? matcher.group(2) : "/";
    }

    private static boolean isAbsoluteHttpUrl(String value) {
        return value.regionMatches(true, 0, "http://", 0, "http://".length())
                || value.regionMatches(true, 0, "https://", 0, "https://".length());
    }

    private static String extractAbsoluteHttpPath(String value) {
        try {
            URI uri = new URI(escapeUriTemplateSyntax(value));
            if (!StringUtils.hasText(uri.getRawAuthority())) {
                return "/";
            }
            String rawPath = uri.getRawPath();
            if (!StringUtils.hasLength(rawPath) || HTTP_URL.matcher(rawPath).find()) {
                return "/";
            }
            return restoreProtectedPercentEscapes(restoreTemplateBraces(rawPath));
        } catch (URISyntaxException exception) {
            return "/";
        }
    }

    private static String removeQueryAndFragment(String value) {
        int queryIndex = value.indexOf('?');
        int fragmentIndex = value.indexOf('#');
        int suffixIndex;
        if (queryIndex < 0) {
            suffixIndex = fragmentIndex;
        } else if (fragmentIndex < 0) {
            suffixIndex = queryIndex;
        } else {
            suffixIndex = Math.min(queryIndex, fragmentIndex);
        }
        return suffixIndex < 0 ? value : value.substring(0, suffixIndex);
    }

    private static String escapeUriTemplateSyntax(String value) {
        return value.replace("%", "%25").replace("{", "%7B").replace("}", "%7D");
    }

    private static String restoreTemplateBraces(String value) {
        return value.replace("%7B", "{").replace("%7D", "}");
    }

    private static String restoreProtectedPercentEscapes(String value) {
        return value.replace("%25", "%");
    }

    private static String safeApiPathField(Object value) {
        return Objects.toString(value, "")
                .replaceAll("[^\\p{L}\\p{N}._/%{}*:-]", "?");
    }

    private static String safeSummaryField(Object value) {
        return Objects.toString(value, "")
                .replaceAll("[^\\p{L}\\p{N}._/{}*:-]", "?");
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
