package com.java.system.agent.ai.tools;

import com.java.system.agent.common.port.RepoApiHostProvider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.function.Function;

@Configuration
class ApiExecutionTools {

    private final RepoApiHostProvider repoApiHostProvider;

    ApiExecutionTools(RepoApiHostProvider repoApiHostProvider) {
        this.repoApiHostProvider = repoApiHostProvider;
    }

    public record ApiRequest(String repoId, String url, String method, Map<String, Object> payload) {}
    public record SpecRequest(String repoId, String endpoint) {}

    @Bean
    @Description("Send an HTTP request to a legacy system API. Provide the repoId to identify which service to call. URL should be relative, e.g., /api/user/1")
    public Function<ApiRequest, String> call_legacy_api(RestClient.Builder restClientBuilder) {
        RestClient restClient = restClientBuilder.build();
        return request -> {
            return repoApiHostProvider.getApiHost(request.repoId())
                .map(apiHost -> {
                    String fullUrl = apiHost + (request.url().startsWith("/") ? "" : "/") + request.url();
                    try {
                        RestClient.RequestBodySpec req = restClient.method(HttpMethod.valueOf(request.method().toUpperCase()))
                                .uri(fullUrl);
                        if (request.payload() != null && !request.payload().isEmpty()) {
                            req.body(request.payload());
                        }
                        return req.retrieve().body(String.class);
                    } catch (Exception e) {
                        return "Error calling legacy API: " + e.getMessage();
                    }
                })
                .orElse("Error: api-host is not configured for repo '" + request.repoId() + "'");
        };
    }

    @Bean
    @Description("Get API definitions from a Swagger/OpenAPI endpoint. Provide the repoId to identify which service to query.")
    public Function<SpecRequest, String> get_api_definition(RestClient.Builder restClientBuilder) {
        RestClient restClient = restClientBuilder.build();
        return request -> {
            return repoApiHostProvider.getApiHost(request.repoId())
                .map(apiHost -> {
                    String fullUrl = apiHost + (request.endpoint().startsWith("/") ? "" : "/") + request.endpoint();
                    try {
                        return restClient.get().uri(fullUrl).retrieve().body(String.class);
                    } catch (Exception e) {
                        return "Error fetching API definition: " + e.getMessage();
                    }
                })
                .orElse("Error: api-host is not configured for repo '" + request.repoId() + "'");
        };
    }

}
