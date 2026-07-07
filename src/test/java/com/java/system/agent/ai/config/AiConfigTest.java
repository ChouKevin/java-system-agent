package com.java.system.agent.ai.config;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.core.io.PathResource;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigTest {

    private final AiConfig aiConfig = new AiConfig();

    @TempDir
    Path tempDir;

    @Test
    void should_apply_credentials_when_credentials_uri_is_configured() throws Exception {
        Path credentialsPath = tempDir.resolve("credentials.json");
        Files.writeString(credentialsPath, """
                {
                  "type": "authorized_user",
                  "client_id": "client-id",
                  "client_secret": "client-secret",
                  "refresh_token": "refresh-token"
                }
                """, StandardCharsets.UTF_8);

        GoogleGenAiConnectionProperties connectionProperties = new GoogleGenAiConnectionProperties();
        connectionProperties.setProjectId("demo-project");
        connectionProperties.setLocation("us-central1");
        connectionProperties.setCredentialsUri(new PathResource(credentialsPath));

        Client client = aiConfig.googleGenAiClient(connectionProperties);

        Optional<GoogleCredentials> configuredCredentials = extractConfiguredCredentials(client);

        assertThat(configuredCredentials).isPresent();
        assertThat(configuredCredentials.orElseThrow()).isInstanceOf(GoogleCredentials.class);
        assertThat(client.vertexAI()).isTrue();
        assertThat(client.project()).isEqualTo("demo-project");
        assertThat(client.location()).isEqualTo("us-central1");
    }

    @SuppressWarnings("unchecked")
    private Optional<GoogleCredentials> extractConfiguredCredentials(Client client) throws Exception {
        Field apiClientField = Client.class.getDeclaredField("apiClient");
        apiClientField.setAccessible(true);
        Object apiClient = apiClientField.get(client);

        Field credentialsField = apiClient.getClass().getSuperclass().getDeclaredField("credentials");
        credentialsField.setAccessible(true);
        return (Optional<GoogleCredentials>) credentialsField.get(apiClient);
    }
}
