package com.java.system.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSecurityPropertiesTest {

    @Test
    void should_bind_empty_token_when_property_is_absent() {
        ApiSecurityProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("api", Bindable.of(ApiSecurityProperties.class));

        assertThat(properties.writeToken()).isEmpty();
        assertThat(properties.hasWriteToken()).isFalse();
    }

    @Test
    void should_bind_token_when_property_is_present() {
        ApiSecurityProperties properties = new Binder(new MapConfigurationPropertySource(
                Map.of("api.write-token", "s3cret")))
                .bindOrCreate("api", Bindable.of(ApiSecurityProperties.class));

        assertThat(properties.writeToken()).isEqualTo("s3cret");
        assertThat(properties.hasWriteToken()).isTrue();
    }

    @Test
    void should_match_when_provided_token_equals_configured_token() {
        ApiSecurityProperties properties = new ApiSecurityProperties("s3cret");

        assertThat(properties.matchesWriteToken("s3cret")).isTrue();
    }

    @Test
    void should_not_match_when_provided_token_differs() {
        ApiSecurityProperties properties = new ApiSecurityProperties("s3cret");

        assertThat(properties.matchesWriteToken("wrong")).isFalse();
    }

    @Test
    void should_not_match_when_provided_token_is_blank() {
        ApiSecurityProperties properties = new ApiSecurityProperties("s3cret");

        assertThat(properties.matchesWriteToken(null)).isFalse();
        assertThat(properties.matchesWriteToken("   ")).isFalse();
    }

    @Test
    void should_not_match_when_token_is_not_configured() {
        ApiSecurityProperties properties = new ApiSecurityProperties("");

        assertThat(properties.matchesWriteToken("anything")).isFalse();
    }
}
