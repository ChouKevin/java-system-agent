package com.java.system.agent.ai.tools;

import com.java.system.agent.analysis.port.RepoDocPort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentToolsTest {

    private static final ToolContext NO_RECORDER = new ToolContext(Map.of("_", "_"));

    // ── readServiceMap ────────────────────────────────────────────────────

    @Test
    void readServiceMap_should_return_content_from_port() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readServiceMap()).thenReturn("## Services\n- test-repo");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readServiceMap(NO_RECORDER);

        assertThat(result).isEqualTo("## Services\n- test-repo");
        verify(port).readServiceMap();
    }

    @Test
    void readServiceMap_should_return_empty_when_port_returns_empty() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readServiceMap()).thenReturn("");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readServiceMap(NO_RECORDER);

        assertThat(result).isEmpty();
    }

    // ── readBusinessMap ───────────────────────────────────────────────────

    @Test
    void readBusinessMap_should_wrap_content_with_header() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readBusinessMap("test-repo")).thenReturn("raw business map content");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readBusinessMap("test-repo", NO_RECORDER);

        assertThat(result).startsWith("========================================\n## 專案: test-repo\n");
        assertThat(result).contains("raw business map content");
        verify(port).readBusinessMap("test-repo");
    }

    @Test
    void readBusinessMap_should_return_empty_when_port_returns_empty() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readBusinessMap("test-repo")).thenReturn("");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readBusinessMap("test-repo", NO_RECORDER);

        assertThat(result).isEmpty();
    }

    // ── readSkillDoc ──────────────────────────────────────────────────────

    @Test
    void readSkillDoc_should_wrap_content_with_header() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readSkillDoc("test-repo", "event")).thenReturn("raw skill doc content");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readSkillDoc("test-repo", "event", NO_RECORDER);

        assertThat(result).startsWith("========================================\n## 業務群組: event (test-repo)\n");
        assertThat(result).contains("raw skill doc content");
        verify(port).readSkillDoc("test-repo", "event");
    }

    @Test
    void readSkillDoc_should_return_empty_when_port_returns_empty() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readSkillDoc("test-repo", "event")).thenReturn("");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readSkillDoc("test-repo", "event", NO_RECORDER);

        assertThat(result).isEmpty();
    }

    // ── recorder ─────────────────────────────────────────────────────────

    @Test
    void readServiceMap_should_record_call_when_recorder_in_context() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readServiceMap()).thenReturn("");
        DocumentTools tools = new DocumentTools(port);
        ToolCallRecorder recorder = mock(ToolCallRecorder.class);
        ToolContext ctx = new ToolContext(Map.of("recorder", recorder));

        tools.readServiceMap(ctx);

        verify(recorder).record(ToolNames.READ_SERVICE_MAP, "{}");
    }

    @Test
    void readBusinessMap_should_record_call_when_recorder_in_context() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readBusinessMap("test-repo")).thenReturn("");
        DocumentTools tools = new DocumentTools(port);
        ToolCallRecorder recorder = mock(ToolCallRecorder.class);
        ToolContext ctx = new ToolContext(Map.of("recorder", recorder));

        tools.readBusinessMap("test-repo", ctx);

        verify(recorder).record(ToolNames.READ_BUSINESS_MAP, "{\"repoId\":\"test-repo\"}");
    }

    @Test
    void readSkillDoc_should_record_call_when_recorder_in_context() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readSkillDoc("test-repo", "event")).thenReturn("");
        DocumentTools tools = new DocumentTools(port);
        ToolCallRecorder recorder = mock(ToolCallRecorder.class);
        ToolContext ctx = new ToolContext(Map.of("recorder", recorder));

        tools.readSkillDoc("test-repo", "event", ctx);

        verify(recorder).record(ToolNames.READ_SKILL_DOC,
                "{\"repoId\":\"test-repo\",\"groupName\":\"event\"}");
    }
}
