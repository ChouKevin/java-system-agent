package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.evidence.CodeEvidenceTracker;
import com.java.system.agent.ai.evidence.EvidenceRequirement;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.ToolCallRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallSummaryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void render_returnsEmpty_whenNoCallsRecorded() {
        String summary = ToolCallSummary.render(
                List.of(), objectMapper, Set.of(ToolNames.FIND_CALL_GRAPH), "title");

        assertThat(summary).isEmpty();
    }

    @Test
    void render_formatsReadBusinessGroupDoc_withRepoAndGroup() {
        String summary = ToolCallSummary.render(
                List.of(new ToolCallRecord(ToolNames.READ_BUSINESS_GROUP_DOC,
                        "{\"repoId\":\"test-repo\",\"groupName\":\"order-checkout\"}")),
                objectMapper,
                Set.of(ToolNames.READ_BUSINESS_GROUP_DOC),
                "title");

        assertThat(summary).contains("[read_business_group_doc]");
        assertThat(summary).contains("repo: `test-repo`");
        assertThat(summary).contains("group: `order-checkout`");
    }

    @Test
    void render_showsRepoOnly_forFindCallGraph() {
        String summary = ToolCallSummary.render(
                List.of(new ToolCallRecord(ToolNames.FIND_CALL_GRAPH,
                        "{\"repoId\":\"BONUS_SERVICE\",\"className\":\"BonusService\",\"methodSignature\":\"calculate\"}")),
                objectMapper,
                Set.of(ToolNames.FIND_CALL_GRAPH),
                "title");

        assertThat(summary).contains("[find_call_graph]");
        assertThat(summary).contains("repo: `BONUS_SERVICE`");
        assertThat(summary).doesNotContain("BonusService");
        assertThat(summary).doesNotContain("calculate");
    }

    @Test
    void render_filtersToRequestedToolNames_only() {
        String summary = ToolCallSummary.render(
                List.of(
                        new ToolCallRecord(ToolNames.READ_SERVICE_MAP, "{}"),
                        new ToolCallRecord(ToolNames.FIND_CALL_GRAPH,
                                "{\"repoId\":\"BONUS_SERVICE\",\"className\":\"Foo\",\"methodSignature\":\"bar\"}")),
                objectMapper,
                Set.of(ToolNames.READ_SERVICE_MAP),
                "title");

        assertThat(summary).contains("read_service_map");
        assertThat(summary).doesNotContain("find_call_graph");
    }

    @Test
    void render_usesFallback_whenArgsJsonIsInvalid() {
        String summary = ToolCallSummary.render(
                List.of(new ToolCallRecord(ToolNames.FIND_CALL_GRAPH, "not-valid-json")),
                objectMapper,
                Set.of(ToolNames.FIND_CALL_GRAPH),
                "title");

        assertThat(summary).contains("[find_call_graph]");
    }

    @Test
    void flatten_includesChildTraceToolCalls() {
        ToolCallRecord rootCall = new ToolCallRecord(
                ToolNames.FIND_CALL_GRAPH, "{\"repoId\":\"root\"}");
        ToolCallRecord childCall = new ToolCallRecord(
                ToolNames.FIND_CALL_GRAPH, "{\"repoId\":\"child\"}");
        LoopTrace child = new LoopTrace(
                "child", "translator", "a", true, List.of(), List.of(childCall));
        LoopTrace root = new LoopTrace(
                "root",
                "analyst",
                "a",
                true,
                List.of(new LoopStep(0, "p", List.of(ToolNames.FIND_CALL_GRAPH), null, List.of(child))),
                List.of(rootCall));

        assertThat(ToolCallSummary.flatten(root)).containsExactly(rootCall, childCall);
    }

    @Test
    void should_render_api_tool_without_code_coordinates_when_api_lookup_is_recorded() {
        String arguments = """
                {"apiPath":"/orders/{id}","httpMethod":"GET","repoId":"order-service"}
                """;
        String summary = ToolCallSummary.render(
                List.of(new ToolCallRecord(
                        ToolNames.FIND_API_CALL_GRAPH,
                        arguments)),
                objectMapper,
                Set.of(ToolNames.FIND_API_CALL_GRAPH),
                "title");

        assertThat(summary).contains("GET", "/orders/{*}", "order-service");
        assertThat(summary).doesNotContain("Controller", "packageName", "methodName");
    }

    @Test
    void should_render_only_safe_path_when_api_argument_is_full_url() {
        String arguments = """
                {
                  "apiPath":"https://api.internal/orders/{id}?token=secret#private-fragment",
                  "httpMethod":"GET",
                  "repoId":"order-service"
                }
                """;

        String summary = ToolCallSummary.render(
                List.of(new ToolCallRecord(ToolNames.FIND_API_CALL_GRAPH, arguments)),
                objectMapper,
                Set.of(ToolNames.FIND_API_CALL_GRAPH),
                "title");

        assertThat(summary).contains("GET /orders/{*} | repo: `order-service`");
        assertThat(summary).doesNotContain(
                "api.internal", "token", "secret", "private-fragment");
    }

    @Test
    void should_render_only_safe_path_when_absolute_url_has_method_prefix_in_api_path() {
        List<String> prefixedApiPaths = List.of(
                "GET https://user:password@internal/orders?token=x#private",
                "GET: https://user:password@internal/orders?token=x#private",
                "[GET] https://user:password@internal/orders?token=x#private",
                "[GET]: https://user:password@internal/orders?token=x#private");

        for (String apiPath : prefixedApiPaths) {
            String arguments = """
                    {
                      "apiPath":"%s",
                      "httpMethod":"GET",
                      "repoId":"order-service"
                    }
                    """.formatted(apiPath);

            String summary = ToolCallSummary.render(
                    List.of(new ToolCallRecord(ToolNames.FIND_API_CALL_GRAPH, arguments)),
                    objectMapper,
                    Set.of(ToolNames.FIND_API_CALL_GRAPH),
                    "title");

            assertThat(summary).contains("GET /orders | repo: `order-service`");
            assertThat(summary).doesNotContain(
                    "internal", "user", "password", "token", "private");
        }
    }

    @Test
    void should_fail_closed_when_absolute_url_has_unsupported_method_prefix() {
        String arguments = """
                {
                  "apiPath":"CONNECT https://user:password@internal/orders?token=x#private",
                  "httpMethod":"GET",
                  "repoId":"order-service"
                }
                """;

        String summary = ToolCallSummary.render(
                List.of(new ToolCallRecord(ToolNames.FIND_API_CALL_GRAPH, arguments)),
                objectMapper,
                Set.of(ToolNames.FIND_API_CALL_GRAPH),
                "title");

        assertThat(summary).contains("GET / | repo: `order-service`");
        assertThat(summary).doesNotContain(
                "CONNECT", "internal", "user", "password", "token", "private");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "`https://user:password@internal/orders?token=x#private`",
            "'GET https://user:password@internal/orders?token=x#private'",
            "\"GET: https://user:password@internal/orders?token=x#private\"",
            "`[GET] https://user:password@internal/orders?token=x#private`",
            "'[GET]: https://user:password@internal/orders?token=x#private'"
    })
    void should_render_only_path_when_supported_url_form_has_matching_outer_wrapper(
            String apiPath) throws Exception {
        String summary = renderApiSummary(apiPath);

        assertThat(summary).contains("GET /orders | repo: `order-service`");
        assertThat(summary).doesNotContain(
                "internal", "user", "password", "token", "private", "https");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "`CONNECT-X https://user:password@internal/orders?token=x#private`",
            "junk https://user:password@internal/orders?token=x#private",
            "prefix [GET] https://user:password@internal/orders?token=x#private",
            "GET https://user:password@internal/orders?token=x#private trailing",
            "https://user:password@internal/orders?token=x trailing"
    })
    void should_fail_closed_when_url_is_not_the_entire_supported_form(String apiPath)
            throws Exception {
        String summary = renderApiSummary(apiPath);

        assertThat(summary).contains("GET / | repo: `order-service`");
        assertThat(summary).doesNotContain(
                "CONNECT-X", "internal", "user", "password", "token", "private", "https");
    }

    @Test
    void should_fail_closed_when_direct_url_contains_nested_url_marker() throws Exception {
        String summary = renderApiSummary(
                "https://safe/pathhttps://user:password@internal/orders");

        assertThat(summary).contains("GET / | repo: `order-service`");
        assertThat(summary).doesNotContain(
                "safe", "path", "internal", "user", "password", "orders", "https");
    }

    @Test
    void should_fail_closed_when_method_prefixed_url_contains_nested_url_marker()
            throws Exception {
        String summary = renderApiSummary(
                "GET: HtTpS://safe/pathHTTP://user:password@internal/orders");

        assertThat(summary).contains("GET / | repo: `order-service`");
        assertThat(summary).doesNotContain(
                "safe", "path", "internal", "user", "password", "orders", "HtTpS", "HTTP");
    }

    @ParameterizedTest
    @CsvSource({
            "https://api.internal/orders/%7Bid%7D, /orders/%7Bid%7D",
            "https://api.internal/orders/%3Cid%3E, /orders/%3Cid%3E",
            "/orders/%7Bid%7D, /orders/%7Bid%7D",
            "/orders/%3Cid%3E, /orders/%3Cid%3E"
    })
    void should_preserve_percent_encoded_template_delimiters_when_rendering_api_path(
            String apiPath, String expectedPath) throws Exception {
        String summary = renderApiSummary(apiPath);

        assertThat(summary).contains("GET %s | repo: `order-service`".formatted(expectedPath));
        assertThat(summary).doesNotContain("/orders/{id}", "/orders/<id>");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{id}",
            "{id:\\d+}",
            ":id",
            "<id>",
            "{{id}}",
            "{*}"
    })
    void should_canonicalize_single_segment_template_when_path_is_relative_or_full_url(
            String templateSegment) throws Exception {
        String expectedPath = "/orders/{*}";

        assertThat(renderApiSummary("/orders/" + templateSegment))
                .contains("GET %s | repo: `order-service`".formatted(expectedPath));
        assertThat(renderApiSummary("https://api.internal/orders/" + templateSegment
                + "?token=secret#private"))
                .contains("GET %s | repo: `order-service`".formatted(expectedPath));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{*path}", "**", "{**}"})
    void should_canonicalize_terminal_catch_all_when_path_is_relative_or_full_url(
            String templateSegment) throws Exception {
        String expectedPath = "/files/{**}";

        assertThat(renderApiSummary("/files/" + templateSegment))
                .contains("GET %s | repo: `order-service`".formatted(expectedPath));
        assertThat(renderApiSummary("https://api.internal/files/" + templateSegment
                + "?token=secret#private"))
                .contains("GET %s | repo: `order-service`".formatted(expectedPath));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "`GET https://api.internal/orders/<id>?token=secret#private`",
            "'[GET]: https://api.internal/orders/{id:\\d+}?token=secret#private'",
            "\"GET: https://api.internal/files/{*path}?token=secret#private\""
    })
    void should_canonicalize_template_when_supported_url_form_has_method_prefix_and_wrapper(
            String apiPath) throws Exception {
        String summary = renderApiSummary(apiPath);

        assertThat(summary).containsAnyOf(
                "GET /orders/{*} | repo: `order-service`",
                "GET /files/{**} | repo: `order-service`");
        assertThat(summary).doesNotContain(
                "api.internal", "token", "secret", "private", "<id>", "\\d+");
    }

    @Test
    void should_apply_slack_safe_allowlist_when_api_summary_fields_are_adversarial() {
        String arguments = """
                {
                  "apiPath":"/orders/{id}<https://evil.example/path>`breakout`\\nNEXT",
                  "httpMethod":"GET<!channel>`method`\\nNEXT",
                  "repoId":"order-service<https://repo.example>`repo`\\nNEXT"
                }
                """;

        String summary = ToolCallSummary.render(
                List.of(new ToolCallRecord(ToolNames.FIND_API_CALL_GRAPH, arguments)),
                objectMapper,
                Set.of(ToolNames.FIND_API_CALL_GRAPH),
                "title");

        assertThat(summary).contains("GET", "/ | repo: `order-service");
        assertThat(summary).doesNotContain("/orders/{id}");
        assertThat(summary).doesNotContain("<!channel>", "<https://", "`breakout`", "`method`");
        assertThat(summary.chars().filter(character -> character == '`').count()).isEqualTo(2L);
    }

    @Test
    void should_never_render_code_coordinates_when_api_arguments_are_adversarial() {
        String arguments = """
                {
                  "apiPath":"/orders/{id}",
                  "httpMethod":"GET",
                  "repoId":"order-service",
                  "packageName":"com.secret.billing.internal",
                  "className":"AdminCredentialController",
                  "methodSignature":"reset(java.lang.String token=super-secret)"
                }
                """;

        String summary = ToolCallSummary.render(
                List.of(new ToolCallRecord(ToolNames.FIND_API_CALL_GRAPH, arguments)),
                objectMapper,
                Set.of(ToolNames.FIND_API_CALL_GRAPH),
                "title");

        assertThat(summary).contains("GET /orders/{*} | repo: `order-service`");
        assertThat(summary).doesNotContain(
                "com.secret", "AdminCredentialController", "reset", "super-secret");
    }

    @Test
    void should_render_evidence_outcome_without_internal_coordinates() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.API_CODE_REQUIRED);
        tracker.recordNotFound(
                ToolNames.FIND_API_CALL_GRAPH,
                "/orders/{id}",
                "API_ROUTE_NOT_FOUND",
                List.of());

        String summary = ToolCallSummary.renderEvidence(tracker.snapshot());

        assertThat(summary).contains("NOT_FOUND", "API_ROUTE_NOT_FOUND");
        assertThat(summary).doesNotContain("className", "methodName", "packageName");
    }

    private String renderApiSummary(String apiPath) throws Exception {
        String arguments = objectMapper.writeValueAsString(Map.of(
                "apiPath", apiPath,
                "httpMethod", "GET",
                "repoId", "order-service"));
        return ToolCallSummary.render(
                List.of(new ToolCallRecord(ToolNames.FIND_API_CALL_GRAPH, arguments)),
                objectMapper,
                Set.of(ToolNames.FIND_API_CALL_GRAPH),
                "title");
    }
}
