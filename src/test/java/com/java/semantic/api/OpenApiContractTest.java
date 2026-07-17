package com.java.semantic.api;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {

    @Test
    void should_describe_exact_repository_paths_and_safe_status_fields_when_contract_is_loaded()
            throws Exception {
        Map<String, Object> document;
        try (InputStream input = Objects.requireNonNull(
                getClass().getResourceAsStream("/openapi/semantic-api-v1.yaml"),
                "OpenAPI document is required")) {
            document = map(new Yaml().load(input));
        }
        Map<String, Object> paths = map(document.get("paths"));

        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
                "/v1/repositories",
                "/v1/repositories/{repoId}",
                "/v1/repositories/{repoId}/ensure",
                "/v1/repositories/{repoId}/sync",
                "/v1/repositories/{repoId}/checkout"));

        Map<String, Object> components = map(document.get("components"));
        assertThat(map(components.get("securitySchemes"))).containsKey("ApiToken");
        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> status = map(schemas.get("RepositoryStatusResponse"));
        Map<String, Object> properties = map(status.get("properties"));

        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "repoId", "mode", "displayName", "currentBranch", "currentRevision", "cloned");
        assertThat(properties).doesNotContainKeys(
                "sourceRoot", "path", "url", "credential", "git", "uri");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
