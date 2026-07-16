package com.java.system.agent.git.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocAdapterTest {

    @TempDir
    Path workDir;

    // ── 截斷規則 ──────────────────────────────────────────────────────────

    @Test
    void should_truncate_business_map_when_entry_point_table_marker_present() throws Exception {
        writeDoc("knowledge/repos/test-repo/business-map.md", """
                # 業務群組
                訂單結帳

                ## 進入點快速對照表
                | API | 方法 |
                """);

        String content = adapter().readBusinessMap("test-repo");

        assertThat(content).contains("訂單結帳");
        assertThat(content).doesNotContain("進入點快速對照表");
        assertThat(content).doesNotContain("| API | 方法 |");
    }

    @Test
    void should_return_whole_business_map_when_marker_absent() throws Exception {
        writeDoc("knowledge/repos/test-repo/business-map.md", "# 業務群組\n訂單結帳\n");

        assertThat(adapter().readBusinessMap("test-repo")).contains("訂單結帳");
    }

    @Test
    void should_return_empty_when_business_map_starts_with_entry_point_table() throws Exception {
        writeDoc("knowledge/repos/test-repo/business-map.md", "\n## 進入點快速對照表\n| API | 方法 |\n");

        assertThat(adapter().readBusinessMap("test-repo")).isEmpty();
    }

    // ── 缺文件回空字串的契約 ────────────────────────────────────────────

    @Test
    void should_return_empty_when_document_missing() {
        // 缺文件時回空字串,由 prompt 轉為「該文件尚未建立」提示
        // 回 null 或丟例外會改變 agent 行為
        assertThat(adapter().readBusinessMap("test-repo")).isEmpty();
        assertThat(adapter().readServiceMap()).isEmpty();
        assertThat(adapter().readSummary("test-repo")).isEmpty();
        assertThat(adapter().readBusinessGroupDoc("test-repo", "order-checkout")).isEmpty();
    }

    // ── knowledge/ 為唯一文件來源 ──────────────────────────────────────

    @Test
    void should_read_service_map_when_it_exists_in_knowledge_root() throws Exception {
        writeDoc("knowledge/service-map.md", "# 服務總覽\n");

        assertThat(adapter().readServiceMap()).contains("服務總覽");
    }

    @Test
    void should_read_business_group_doc_when_it_exists_in_knowledge_root() throws Exception {
        writeDoc("knowledge/repos/test-repo/business-groups/order-checkout.md", "# 訂單結帳\n");

        assertThat(adapter().readBusinessGroupDoc("test-repo", "order-checkout")).contains("訂單結帳");
    }

    @Test
    void should_read_summary_when_it_exists_in_knowledge_root() throws Exception {
        writeDoc("knowledge/repos/test-repo/summary.md", "核心訂單服務\n");

        assertThat(adapter().readSummary("test-repo")).contains("核心訂單服務");
    }

    @Test
    void should_return_empty_when_document_only_exists_at_legacy_repos_path() throws Exception {
        writeDoc("repos/test-repo/docs/business-map.md", "# 舊路徑不應被讀取\n");

        assertThat(adapter().readBusinessMap("test-repo"))
                .as("documents must come from knowledge/ only; repos/ is mutable clone territory")
                .isEmpty();
    }

    // ── 路徑穿越防護 ──────────────────────────────────────────────────────

    @Test
    void should_reject_repo_id_when_it_escapes_the_knowledge_root() {
        assertThatThrownBy(() -> adapter().readBusinessMap("../../etc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter().readBusinessMap(".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter().readBusinessGroupDoc("..", "order-checkout"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── readSummary 不驗證格式:repoId 來源為設定鍵,非 LLM 輸入 ──────────

    @Test
    void should_return_empty_when_summary_repo_id_is_unconventional() {
        // readSummary 唯一呼叫來源是 RepoRegistryAdapter.all(),餵入 git.repos 設定鍵;
        // CacheWarmerService 於 @PostConstruct 呼叫此路徑,拋出例外會讓應用程式無法啟動
        assertThat(adapter().readSummary("Not-A-Valid-Id")).isEmpty();
    }

    @Test
    void should_reject_group_name_when_it_escapes_the_knowledge_root() {
        assertThatThrownBy(() -> adapter().readBusinessGroupDoc("test-repo", "../../../summary"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_id_when_it_is_absolute_or_hidden_or_blank() {
        assertThatThrownBy(() -> adapter().readBusinessMap("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter().readBusinessMap(".hidden"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter().readBusinessMap("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter().readBusinessGroupDoc("test-repo", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_not_leak_content_when_traversal_targets_a_real_file_outside_knowledge_root()
            throws Exception {
        writeDoc("secrets.md", "TOP SECRET");

        assertThatThrownBy(() -> adapter().readBusinessGroupDoc("test-repo", "../../../../secrets"))
                .as("a traversal that would otherwise resolve to a readable file must be rejected")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_accept_id_when_it_matches_the_conventional_repo_naming() throws Exception {
        writeDoc("knowledge/repos/bonus-service.v2/business-groups/order_checkout.md", "# 允許\n");

        assertThat(adapter().readBusinessGroupDoc("bonus-service.v2", "order_checkout")).contains("允許");
    }

    private KnowledgeDocAdapter adapter() {
        return new KnowledgeDocAdapter(workDir.toString());
    }

    private void writeDoc(String relativePath, String content) throws Exception {
        Path target = workDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
