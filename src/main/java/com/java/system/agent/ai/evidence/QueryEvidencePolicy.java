package com.java.system.agent.ai.evidence;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class QueryEvidencePolicy {

    private static final int INTENT_PATTERN_FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern POLITE_PREFIX = Pattern.compile(
            "^(?:(?:你好|您好|嗨|請問)[\\s，,。！!]*)+");
    private static final Pattern TRAILING_SENTENCE_PUNCTUATION = Pattern.compile(
            "[\\s，,。！？!；;]+$");
    private static final Pattern EXPLICIT_API_INTENT = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:api|endpoint)(?![A-Za-z0-9_])|端點|路由",
            INTENT_PATTERN_FLAGS);
    private static final List<Pattern> DOCS_ONLY_INTENTS = List.of(
            intent("(?:(?:這個|本)?系統)?有哪些服務(?:[，,]?各自負責什麼)?"),
            intent("(?:這個|本)?系統(?:的)?概覽"),
            intent("(?:各個|每個|各)服務(?:各自)?負責什麼"),
            intent("(?:請)?(?:提供|說明|列出)?(?:這個|本)?(?:系統)?(?:的)?文件摘要"),
            intent("(?:請)?(?:列出)?(?:有哪些)?業務群組(?:清單)?"),
            intent("業務群組(?:有哪些|清單)"),
            intent("(?:請)?(?:列出)?候選\\s*repo(?:\\s*清單)?"),
            intent("(?:請)?(?:列出)?repo\\s*清單"),
            intent(".+(?:屬於|在哪個)(?:業務群組|repo)"),
            intent(".+(?:由)?哪個(?:業務群組|repo)(?:負責|處理)"),
            intent("哪個服務負責會員"),
            intent("[A-Za-z0-9][A-Za-z0-9._-]*-service\\s*負責什麼"),
            intent("(?:怎麼|如何)使用(?:這個|本)?\\s*(?:bot|機器人|系統)?"),
            intent("(?:(?:這個|本)?\\s*(?:bot|機器人|系統)\\s*)?(?:怎麼|如何)使用"));
    private static final List<String> BUSINESS_BEHAVIOR_MARKERS = List.of(
            "流程", "規則", "條件", "計算", "判斷", "副作用", "何時", "為什麼");

    private final ApiQueryParser apiQueryParser;

    public QueryEvidencePolicy() {
        this(new ApiQueryParser());
    }

    QueryEvidencePolicy(ApiQueryParser apiQueryParser) {
        this.apiQueryParser = Objects.requireNonNull(apiQueryParser,
                "apiQueryParser must not be null");
    }

    public EvidenceRequirement classify(String query) {
        if (!StringUtils.hasText(query)) {
            return EvidenceRequirement.DOCS_ONLY;
        }
        if (apiQueryParser.parse(query).isPresent()) {
            return EvidenceRequirement.API_CODE_REQUIRED;
        }
        if (hasExplicitApiIntent(query)) {
            return EvidenceRequirement.API_CODE_REQUIRED;
        }
        if (isBusinessBehavior(query)) {
            return EvidenceRequirement.BUSINESS_CODE_REQUIRED;
        }
        if (isDocsOnly(query)) {
            return EvidenceRequirement.DOCS_ONLY;
        }
        return EvidenceRequirement.BUSINESS_CODE_REQUIRED;
    }

    private boolean isDocsOnly(String query) {
        String normalizedQuery = normalizeDocsOnlyCandidate(query);
        if (!StringUtils.hasText(normalizedQuery)) {
            return true;
        }
        return DOCS_ONLY_INTENTS.stream()
                .anyMatch(pattern -> pattern.matcher(normalizedQuery).matches());
    }

    private boolean isBusinessBehavior(String query) {
        return BUSINESS_BEHAVIOR_MARKERS.stream().anyMatch(query::contains);
    }

    private boolean hasExplicitApiIntent(String query) {
        return EXPLICIT_API_INTENT.matcher(query).find();
    }

    private String normalizeDocsOnlyCandidate(String query) {
        String withoutPolitePrefix = POLITE_PREFIX.matcher(query.strip()).replaceFirst("");
        return TRAILING_SENTENCE_PUNCTUATION.matcher(withoutPolitePrefix.strip())
                .replaceFirst("");
    }

    private static Pattern intent(String expression) {
        return Pattern.compile(expression, INTENT_PATTERN_FLAGS);
    }
}
