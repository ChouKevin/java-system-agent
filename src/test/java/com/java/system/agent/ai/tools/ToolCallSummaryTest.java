package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.ToolCallRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
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
}
