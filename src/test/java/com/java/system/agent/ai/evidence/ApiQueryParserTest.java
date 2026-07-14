package com.java.system.agent.ai.evidence;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApiQueryParserTest {

    @Test
    void should_extract_method_and_path_when_question_contains_explicit_api() {
        Optional<ApiQueryHint> result = new ApiQueryParser().parse(
                "請問 GET /orders/{orderId}?expand=items 的流程？");

        assertThat(result).contains(new ApiQueryHint(
                "GET", "/orders/{orderId}?expand=items"));
    }

    @Test
    void should_normalize_method_when_explicit_api_uses_lowercase_method() {
        Optional<ApiQueryHint> result = new ApiQueryParser().parse(
                "patch: /orders/{orderId} 要做什麼？");

        assertThat(result).contains(new ApiQueryHint("PATCH", "/orders/{orderId}"));
    }

    @Test
    void should_extract_url_without_method_when_question_contains_absolute_url() {
        Optional<ApiQueryHint> result = new ApiQueryParser().parse(
                "請說明 https://internal/orders。 ");

        assertThat(result).contains(new ApiQueryHint("", "https://internal/orders"));
    }

    @Test
    void should_return_empty_when_query_has_no_explicit_api() {
        ApiQueryParser parser = new ApiQueryParser();

        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("  ")).isEmpty();
        assertThat(parser.parse("請說明訂單建立流程")).isEmpty();
    }
}
