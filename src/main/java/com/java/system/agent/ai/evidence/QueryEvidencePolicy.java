package com.java.system.agent.ai.evidence;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

public final class QueryEvidencePolicy {

    private static final List<String> DOCS_ONLY_MARKERS = List.of(
            "系統概覽", "有哪些服務", "哪個服務", "負責什麼", "文件摘要",
            "業務群組", "候選 repo", "repo 清單", "怎麼使用", "如何使用", "你好");
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
        if (isBusinessBehavior(query)) {
            return EvidenceRequirement.BUSINESS_CODE_REQUIRED;
        }
        if (isDocsOnly(query)) {
            return EvidenceRequirement.DOCS_ONLY;
        }
        return EvidenceRequirement.BUSINESS_CODE_REQUIRED;
    }

    private boolean isDocsOnly(String query) {
        return StringUtils.hasText(query)
                && DOCS_ONLY_MARKERS.stream().anyMatch(query::contains);
    }

    private boolean isBusinessBehavior(String query) {
        return BUSINESS_BEHAVIOR_MARKERS.stream().anyMatch(query::contains);
    }
}
