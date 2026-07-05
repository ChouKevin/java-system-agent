package com.java.system.agent.git.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepoDocAdapterTest {

    @TempDir
    Path root;

    private RepoDocAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RepoDocAdapter(root.toString());
    }

    // ── readServiceMap ────────────────────────────────────────────────────

    @Test
    void readServiceMap_should_return_file_content_when_file_exists() throws IOException {
        Path serviceMap = root.resolve("repos/service-map.md");
        Files.createDirectories(serviceMap.getParent());
        Files.writeString(serviceMap, "## Services\n- test-repo");

        String result = adapter.readServiceMap();

        assertThat(result).isEqualTo("## Services\n- test-repo");
    }

    @Test
    void readServiceMap_should_return_empty_when_file_missing() {
        String result = adapter.readServiceMap();

        assertThat(result).isEmpty();
    }

    // ── readBusinessMap ───────────────────────────────────────────────────

    @Test
    void readBusinessMap_should_return_content_before_entry_point_table() throws IOException {
        Path docPath = root.resolve("repos/test-repo/docs/business-map.md");
        Files.createDirectories(docPath.getParent());
        Files.writeString(docPath, "## Business\nsome content\n## 進入點快速對照表\nshould be excluded");

        String result = adapter.readBusinessMap("test-repo");

        assertThat(result).isEqualTo("## Business\nsome content");
    }

    @Test
    void readBusinessMap_should_return_full_content_when_no_entry_point_table() throws IOException {
        Path docPath = root.resolve("repos/test-repo/docs/business-map.md");
        Files.createDirectories(docPath.getParent());
        Files.writeString(docPath, "## Business\nno table here");

        String result = adapter.readBusinessMap("test-repo");

        assertThat(result).isEqualTo("## Business\nno table here");
    }

    @Test
    void readBusinessMap_should_return_empty_when_file_missing() {
        String result = adapter.readBusinessMap("test-repo");

        assertThat(result).isEmpty();
    }

    @Test
    void readBusinessMap_should_return_empty_when_file_starts_with_entry_point_table() throws IOException {
        Path docPath = root.resolve("repos/test-repo/docs/business-map.md");
        Files.createDirectories(docPath.getParent());
        Files.writeString(docPath, "\n## 進入點快速對照表\nsome content");

        String result = adapter.readBusinessMap("test-repo");

        assertThat(result).isEmpty();
    }

    // ── readBusinessGroupDoc ───────────────────────────────────────────────

    @Test
    void readBusinessGroupDoc_should_return_file_content_when_file_exists() throws IOException {
        Path docPath = root.resolve("repos/test-repo/docs/business-groups/order-checkout.md");
        Files.createDirectories(docPath.getParent());
        Files.writeString(docPath, "# 訂單結帳\n建立訂單並準備付款");

        String result = adapter.readBusinessGroupDoc("test-repo", "order-checkout");

        assertThat(result).isEqualTo("# 訂單結帳\n建立訂單並準備付款");
    }

    @Test
    void readBusinessGroupDoc_should_return_empty_when_groupName_is_blank() {
        String result = adapter.readBusinessGroupDoc("test-repo", "  ");

        assertThat(result).isEmpty();
    }

    @Test
    void readBusinessGroupDoc_should_return_empty_when_groupName_is_null() {
        String result = adapter.readBusinessGroupDoc("test-repo", null);

        assertThat(result).isEmpty();
    }

    @Test
    void readBusinessGroupDoc_should_return_empty_when_file_missing() {
        String result = adapter.readBusinessGroupDoc("test-repo", "nonexistent");

        assertThat(result).isEmpty();
    }

    // ── readSummary ────────────────────────────────────────────────────────

    @Test
    void readSummary_should_return_file_content_when_file_exists() throws IOException {
        Path docPath = root.resolve("repos/test-repo/docs/summary.md");
        Files.createDirectories(docPath.getParent());
        Files.writeString(docPath, "核心獎勵結算中心");

        String result = adapter.readSummary("test-repo");

        assertThat(result).isEqualTo("核心獎勵結算中心");
    }

    @Test
    void readSummary_should_return_empty_when_file_missing() {
        String result = adapter.readSummary("test-repo");

        assertThat(result).isEmpty();
    }
}
