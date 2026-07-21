package com.java.system.agent.ai.tools;

import com.java.system.agent.analysis.port.RepoDocPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentToolsTest {

    @Test
    void readServiceMap_should_return_content_from_port() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readServiceMap()).thenReturn("## Services\n- demo-repo");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readServiceMap();

        assertThat(result).isEqualTo("## Services\n- demo-repo");
        verify(port).readServiceMap();
    }

    @Test
    void readServiceMap_should_return_empty_when_port_returns_empty() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readServiceMap()).thenReturn("");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readServiceMap();

        assertThat(result).isEmpty();
    }

    @Test
    void readBusinessMap_should_wrap_content_with_header() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readBusinessMap("demo-repo")).thenReturn("raw business map content");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readBusinessMap("demo-repo");

        assertThat(result).startsWith("========================================\n## 專案: demo-repo\n");
        assertThat(result).contains("raw business map content");
        verify(port).readBusinessMap("demo-repo");
    }

    @Test
    void readBusinessMap_should_return_empty_when_port_returns_empty() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readBusinessMap("demo-repo")).thenReturn("");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readBusinessMap("demo-repo");

        assertThat(result).isEmpty();
    }

    @Test
    void readBusinessGroupDoc_should_wrap_content_with_header() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readBusinessGroupDoc("demo-repo", "order-checkout")).thenReturn("raw business group content");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readBusinessGroupDoc("demo-repo", "order-checkout");

        assertThat(result).startsWith("========================================\n## 業務群組: order-checkout (demo-repo)\n");
        assertThat(result).contains("raw business group content");
        verify(port).readBusinessGroupDoc("demo-repo", "order-checkout");
    }

    @Test
    void readBusinessGroupDoc_should_return_empty_when_port_returns_empty() {
        RepoDocPort port = mock(RepoDocPort.class);
        when(port.readBusinessGroupDoc("demo-repo", "order-checkout")).thenReturn("");
        DocumentTools tools = new DocumentTools(port);

        String result = tools.readBusinessGroupDoc("demo-repo", "order-checkout");

        assertThat(result).isEmpty();
    }
}
