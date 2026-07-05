package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RequestScopedToolCallRecorderTest {

    private RequestScopedToolCallRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new RequestScopedToolCallRecorder(new ObjectMapper());
    }

    // ── getSummaryForTools: empty cases ───────────────────────────────────────

    @Test
    void getSummaryForTools_returnsEmpty_whenNoCallsRecorded() {
        assertThat(recorder.getSummaryForTools(Set.of(ToolNames.FIND_CALL_GRAPH), "title")).isEmpty();
    }

    @Test
    void getSummaryForTools_returnsEmpty_whenNoMatchingToolNames() {
        recorder.record(ToolNames.READ_SERVICE_MAP, "{}");

        assertThat(recorder.getSummaryForTools(Set.of(ToolNames.FIND_CALL_GRAPH), "title")).isEmpty();
    }

    // ── getSummaryForTools: formatting per tool ───────────────────────────────

    @Test
    void getSummaryForTools_formatsReadServiceMap_correctly() {
        recorder.record(ToolNames.READ_SERVICE_MAP, "{}");

        String summary = recorder.getSummaryForTools(Set.of(ToolNames.READ_SERVICE_MAP), "title");

        assertThat(summary).contains("[read_service_map]");
    }

    @Test
    void getSummaryForTools_formatsReadBusinessMap_withRepoId() {
        recorder.record(ToolNames.READ_BUSINESS_MAP, "{\"repoId\":\"BONUS_SERVICE\"}");

        String summary = recorder.getSummaryForTools(Set.of(ToolNames.READ_BUSINESS_MAP), "title");

        assertThat(summary).contains("[read_business_map]");
        assertThat(summary).contains("BONUS_SERVICE");
    }

    @Test
    void getSummaryForTools_formatsReadBusinessGroupDoc_withRepoAndGroup() {
        recorder.record(ToolNames.READ_BUSINESS_GROUP_DOC,
                "{\"repoId\":\"test-repo\",\"groupName\":\"order-checkout\"}");

        String summary = recorder.getSummaryForTools(Set.of(ToolNames.READ_BUSINESS_GROUP_DOC), "title");

        assertThat(summary).contains("[read_business_group_doc]");
        assertThat(summary).contains("repo: `test-repo`");
        assertThat(summary).contains("group: `order-checkout`");
    }

    @Test
    void getSummaryForTools_formatsFindCallGraph_withDetails() {
        recorder.record(ToolNames.FIND_CALL_GRAPH,
                "{\"repoId\":\"BONUS_SERVICE\",\"packageName\":\"com\",\"className\":\"BonusService\",\"methodSignature\":\"calculate\"}");

        String summary = recorder.getSummaryForTools(Set.of(ToolNames.FIND_CALL_GRAPH), "title");

        assertThat(summary).contains("[find_call_graph]");
        assertThat(summary).contains("BONUS_SERVICE");
        assertThat(summary).contains("BonusService");
        assertThat(summary).contains("calculate");
    }

    // ── getSummaryForTools: title and numbering ───────────────────────────────

    @Test
    void getSummaryForTools_includesTitle_whenMatchingCallExists() {
        recorder.record(ToolNames.FIND_CALL_GRAPH,
                "{\"repoId\":\"BONUS_SERVICE\",\"packageName\":\"com\",\"className\":\"Foo\",\"methodSignature\":\"bar\"}");

        String summary = recorder.getSummaryForTools(Set.of(ToolNames.FIND_CALL_GRAPH), "📋 程式碼查詢紀錄");

        assertThat(summary).contains("📋 程式碼查詢紀錄");
    }

    @Test
    void getSummaryForTools_numbersMultipleMatchingEntries() {
        recorder.record(ToolNames.FIND_CALL_GRAPH,
                "{\"repoId\":\"BONUS_SERVICE\",\"packageName\":\"com\",\"className\":\"A\",\"methodSignature\":\"methodA\"}");
        recorder.record(ToolNames.FIND_CALL_GRAPH,
                "{\"repoId\":\"BONUS_SERVICE\",\"packageName\":\"com\",\"className\":\"B\",\"methodSignature\":\"methodB\"}");

        String summary = recorder.getSummaryForTools(Set.of(ToolNames.FIND_CALL_GRAPH), "title");

        assertThat(summary).contains("1. [find_call_graph]");
        assertThat(summary).contains("2. [find_call_graph]");
    }

    @Test
    void getSummaryForTools_filtersToRequestedToolNames_only() {
        recorder.record(ToolNames.READ_SERVICE_MAP, "{}");
        recorder.record(ToolNames.FIND_CALL_GRAPH,
                "{\"repoId\":\"BONUS_SERVICE\",\"packageName\":\"com\",\"className\":\"Foo\",\"methodSignature\":\"bar\"}");

        String summary = recorder.getSummaryForTools(Set.of(ToolNames.READ_SERVICE_MAP), "title");

        assertThat(summary).contains("read_service_map");
        assertThat(summary).doesNotContain("find_call_graph");
    }

    // ── record: edge cases ────────────────────────────────────────────────────

    @Test
    void record_handlesNullArgs_gracefully() {
        recorder.record(ToolNames.READ_SERVICE_MAP, null);

        String summary = recorder.getSummaryForTools(Set.of(ToolNames.READ_SERVICE_MAP), "title");

        assertThat(summary).contains("read_service_map");
    }

    @Test
    void getSummaryForTools_usesFallback_whenArgsJsonIsInvalid() {
        recorder.record(ToolNames.FIND_CALL_GRAPH, "not-valid-json");

        String summary = recorder.getSummaryForTools(Set.of(ToolNames.FIND_CALL_GRAPH), "title");

        assertThat(summary).contains("[find_call_graph]");
    }

    @Test
    void getSummaryForTools_usesUnknownFallbacks_whenFieldsMissing() {
        recorder.record(ToolNames.FIND_CALL_GRAPH, "{}");

        String summary = recorder.getSummaryForTools(Set.of(ToolNames.FIND_CALL_GRAPH), "title");

        assertThat(summary).contains("unknown-repo");
        assertThat(summary).contains("unknown-class");
        assertThat(summary).contains("unknown-method");
    }
}
