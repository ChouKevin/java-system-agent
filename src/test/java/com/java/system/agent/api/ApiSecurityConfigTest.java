package com.java.system.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSecurityConfigTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void should_register_filter_on_git_paths_only() {
        ApiSecurityConfig config = new ApiSecurityConfig();

        FilterRegistrationBean<ApiWriteProtectionFilter> registration =
                config.apiWriteProtectionFilterRegistration(
                        new ApiSecurityProperties("s3cret"), objectMapper);

        assertThat(registration.getUrlPatterns()).containsExactly("/git/*");
        assertThat(registration.getFilter()).isInstanceOf(ApiWriteProtectionFilter.class);
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void should_create_registration_when_token_is_not_configured() {
        ApiSecurityConfig config = new ApiSecurityConfig();

        FilterRegistrationBean<ApiWriteProtectionFilter> registration =
                config.apiWriteProtectionFilterRegistration(
                        new ApiSecurityProperties(""), objectMapper);

        assertThat(registration.getFilter()).isInstanceOf(ApiWriteProtectionFilter.class);
    }
}
