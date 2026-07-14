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
    void should_use_root_path_when_full_url_query_contains_route_like_slashes() {
        NormalizedApiPath result = ApiPathNormalizer.normalize(
                "https://example.internal?redirect=/orders/42", "GET");

        assertThat(result.path()).isEqualTo("/");
    }

    @Test
    void should_use_root_path_when_full_url_fragment_contains_route_like_slashes() {
        NormalizedApiPath result = ApiPathNormalizer.normalize(
                "https://example.internal#section/orders/42", "GET");

        assertThat(result.path()).isEqualTo("/");
    }

    @Test
    void should_reject_input_when_http_url_has_no_authority() {
        assertThatThrownBy(() -> ApiPathNormalizer.normalize("https:///orders/42", "GET"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_input_when_absolute_url_uses_unsupported_scheme() {
        assertThatThrownBy(() -> ApiPathNormalizer.normalize(
                "ftp://example.internal/orders/42", "GET"))
                .isInstanceOf(IllegalArgumentException.class);
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

    @Test
    void should_normalize_root_path_when_input_has_method_prefix() {
        NormalizedApiPath result = ApiPathNormalizer.normalize("GET /", "");

        assertThat(result.path()).isEqualTo("/");
        assertThat(result.httpMethod()).isEqualTo("GET");
    }

    @Test
    void should_preserve_non_ascii_percent_octets_when_path_contains_utf8_sequence() {
        NormalizedApiPath result = ApiPathNormalizer.normalize("/caf%C3%A9", "GET");

        assertThat(result.path()).isEqualTo("/caf%C3%A9");
    }

    @Test
    void should_decode_ascii_unreserved_when_path_contains_safe_escape() {
        NormalizedApiPath result = ApiPathNormalizer.normalize("/users/%7Eowner", "GET");

        assertThat(result.path()).isEqualTo("/users/~owner");
    }

    @Test
    void should_preserve_encoded_template_delimiters_when_path_contains_literal_data() {
        NormalizedApiPath result = ApiPathNormalizer.normalize(
                "/users/%7Bid%7D/%3Cid%3E", "GET");

        assertThat(result.path()).isEqualTo("/users/%7Bid%7D/%3Cid%3E");
    }
}
