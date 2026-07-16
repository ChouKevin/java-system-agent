package com.java.system.agent.ai.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnalysisToolContractTest {

    @Test
    void should_expose_frozen_tool_names_when_registered() {
        assertThat(toolNames(DocumentTools.class))
                .containsExactlyInAnyOrder("read_service_map", "read_business_map", "read_business_group_doc");
        assertThat(toolNames(AgentAnalysisTools.class))
                .containsExactlyInAnyOrder("find_call_graph", "find_api_call_graph");
        assertThat(toolNames(CallGraphExpandTools.class))
                .containsExactly("find_call_graph");
    }

    @Test
    void should_keep_the_deliberate_find_call_graph_name_collision() {
        assertThat(toolNames(AgentAnalysisTools.class))
                .as("the analyst and translator loops share this tool name on purpose; "
                        + "they return different types and are disambiguated by which loop registers them")
                .contains("find_call_graph");
        assertThat(toolNames(CallGraphExpandTools.class)).contains("find_call_graph");
    }

    private static List<String> toolNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(Objects::nonNull)
                .map(Tool::name)
                .toList();
    }
}
