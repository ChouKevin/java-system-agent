package com.java.system.agent.analysis.trie;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiPathNormalizerTest {

    @Test
    void should_normalize_full_url_and_query_when_input_contains_path_parameter() {
        NormalizedApiPath result = ApiPathNormalizer.normalize(
                "GET https://example.internal//orders/:id/?expand=items#detail", "");

        assertThat(result.path()).isEqualTo("/orders/{*}");
        assertThat(result.httpMethod()).isEqualTo("GET");
    }

    @Test
    void should_normalize_supported_parameter_notations_when_template_names_differ() {
        assertThat(ApiPathNormalizer.normalize("/orders/{orderId}", "GET").path())
                .isEqualTo("/orders/{*}");
        assertThat(ApiPathNormalizer.normalize("/orders/{id:\\d+}", "GET").path())
                .isEqualTo("/orders/{*}");
        assertThat(ApiPathNormalizer.normalize("/orders/<id>", "GET").path())
                .isEqualTo("/orders/{*}");
        assertThat(ApiPathNormalizer.normalize("/orders/{{id}}", "GET").path())
                .isEqualTo("/orders/{*}");
    }

    @Test
    void should_keep_encoded_slash_inside_segment_when_path_contains_percent_2f() {
        NormalizedApiPath result = ApiPathNormalizer.normalize("/files/a%2fb", "get");

        assertThat(result.path()).isEqualTo("/files/a%2Fb");
        assertThat(result.httpMethod()).isEqualTo("GET");
    }

    @Test
    void should_normalize_terminal_catch_all_when_route_uses_spring_or_double_star_style() {
        assertThat(ApiPathNormalizer.normalize("/files/{*path}", "GET").path())
                .isEqualTo("/files/{**}");
        assertThat(ApiPathNormalizer.normalize("/files/**", "GET").path())
                .isEqualTo("/files/{**}");
    }

    @Test
    void should_reject_input_when_prefixed_and_explicit_methods_conflict() {
        assertThatThrownBy(() -> ApiPathNormalizer.normalize("GET /orders/1", "POST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflict");
    }
}
