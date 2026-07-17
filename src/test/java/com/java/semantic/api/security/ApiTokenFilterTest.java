package com.java.semantic.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenFilterTest {

    private static final String CONFIGURED_TOKEN = "s3cret-token";

    @Test
    void should_reject_every_request_when_token_is_not_configured() throws Exception {
        MockHttpServletResponse response = invoke(filterWith(""), "GET", "/v1/repositories", CONFIGURED_TOKEN);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void should_reject_when_token_header_is_missing() throws Exception {
        MockHttpServletResponse response = invokeWithoutToken(
                filterWith(CONFIGURED_TOKEN), "GET", "/v1/repositories");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void should_reject_when_token_is_wrong() throws Exception {
        MockHttpServletResponse response = invoke(filterWith(CONFIGURED_TOKEN), "GET", "/v1/repositories", "wrong");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void should_pass_through_when_token_matches() throws Exception {
        MockHttpServletResponse response =
                invoke(filterWith(CONFIGURED_TOKEN), "GET", "/v1/repositories", CONFIGURED_TOKEN);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void should_reject_read_requests_too_when_token_is_missing() throws Exception {
        // 與 agent 的 filter 不同:此服務的讀取端點會回傳私有原始碼的分析結果,GET 不豁免
        MockHttpServletResponse response = invokeWithoutToken(
                filterWith(CONFIGURED_TOKEN), "GET", "/v1/repositories/x");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void should_allow_health_without_a_token_when_compose_probes_it() throws Exception {
        MockHttpServletResponse response = invokeWithoutToken(
                filterWith(CONFIGURED_TOKEN), "GET", "/actuator/health");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void should_reject_when_path_only_starts_with_the_health_path() throws Exception {
        MockHttpServletResponse response = invokeWithoutToken(
                filterWith(CONFIGURED_TOKEN), "GET", "/actuator/health-extra");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    private ApiTokenFilter filterWith(String token) {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setApiToken(token);
        return new ApiTokenFilter(properties);
    }

    private MockHttpServletResponse invoke(ApiTokenFilter filter, String method, String uri, String token)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader(ApiTokenFilter.API_TOKEN_HEADER, token);
        return invoke(filter, request);
    }

    private MockHttpServletResponse invokeWithoutToken(ApiTokenFilter filter, String method, String uri)
            throws Exception {
        return invoke(filter, new MockHttpServletRequest(method, uri));
    }

    private MockHttpServletResponse invoke(ApiTokenFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
