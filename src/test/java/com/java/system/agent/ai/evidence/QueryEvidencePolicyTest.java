package com.java.system.agent.ai.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryEvidencePolicyTest {

    private final QueryEvidencePolicy policy = new QueryEvidencePolicy();

    @Test
    void should_require_api_evidence_when_query_contains_http_route() {
        assertThat(policy.classify("POST https://internal/orders 要做什麼？"))
                .isEqualTo(EvidenceRequirement.API_CODE_REQUIRED);
    }

    @Test
    void should_require_api_evidence_when_query_contains_url_without_method() {
        assertThat(policy.classify("請說明 https://internal/orders 的用途"))
                .isEqualTo(EvidenceRequirement.API_CODE_REQUIRED);
    }

    @Test
    void should_allow_docs_only_when_query_requests_service_overview() {
        assertThat(policy.classify("這個系統有哪些服務，各自負責什麼？"))
                .isEqualTo(EvidenceRequirement.DOCS_ONLY);
        assertThat(policy.classify("你好，怎麼使用這個 bot？"))
                .isEqualTo(EvidenceRequirement.DOCS_ONLY);
    }

    @Test
    void should_require_business_evidence_when_query_asks_actual_rule() {
        assertThat(policy.classify("獎金在什麼條件下會被取消？"))
                .isEqualTo(EvidenceRequirement.BUSINESS_CODE_REQUIRED);
        assertThat(policy.classify("哪個服務負責獎金取消規則？"))
                .isEqualTo(EvidenceRequirement.BUSINESS_CODE_REQUIRED);
    }

    @Test
    void should_prioritize_business_behavior_when_query_also_contains_docs_marker() {
        assertThat(policy.classify("哪個服務負責訂單計算流程？"))
                .isEqualTo(EvidenceRequirement.BUSINESS_CODE_REQUIRED);
    }

    @Test
    void should_require_business_evidence_when_query_has_no_known_marker() {
        assertThat(policy.classify("告訴我獎金取消的細節"))
                .isEqualTo(EvidenceRequirement.BUSINESS_CODE_REQUIRED);
    }

    @Test
    void should_allow_docs_only_when_query_is_blank() {
        assertThat(policy.classify(null)).isEqualTo(EvidenceRequirement.DOCS_ONLY);
        assertThat(policy.classify("  ")).isEqualTo(EvidenceRequirement.DOCS_ONLY);
    }
}
