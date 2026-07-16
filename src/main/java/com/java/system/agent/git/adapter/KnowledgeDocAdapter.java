package com.java.system.agent.git.adapter;

import com.java.system.agent.analysis.port.RepoDocPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 讀取 agent 自有的 knowledge/ 業務文件
 *
 * 文件不再來自 repos/{repoId}/docs — 那是可變的 clone,git pull 會覆蓋手寫內容
 */
@Slf4j
@Component
class KnowledgeDocAdapter implements RepoDocPort {

    /** business-map 於此標記處截斷,避免進入點對照表進入分析 LLM 的 context */
    private static final String ENTRY_POINT_TABLE_MARKER = "\n## 進入點快速對照表";

    /** repoId / groupName 會併入檔案路徑,必須先驗證,不可信任 LLM 傳入值 */
    private static final Pattern SAFE_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    private final String workDir;

    KnowledgeDocAdapter() {
        this(".");
    }

    /** 測試用:指定工作目錄 */
    KnowledgeDocAdapter(String workDir) {
        this.workDir = workDir;
    }

    @Override
    public String readServiceMap() {
        return readFile(Path.of(workDir, "knowledge", "service-map.md"));
    }

    @Override
    public String readBusinessMap(String repoId) {
        String content = readFile(knowledgeRoot(repoId).resolve("business-map.md"));
        if (!StringUtils.hasText(content)) {
            return content;
        }
        int cutIndex = content.indexOf(ENTRY_POINT_TABLE_MARKER);
        return cutIndex >= 0 ? content.substring(0, cutIndex) : content;
    }

    @Override
    public String readBusinessGroupDoc(String repoId, String groupName) {
        Path groupDoc = knowledgeRoot(repoId)
                .resolve("business-groups")
                .resolve(requireSafeId(groupName, "groupName") + ".md");
        return readFile(groupDoc);
    }

    /**
     * 讀取 summary,repoId 來源為啟動時的 git.repos 設定鍵,非 LLM 傳入值
     *
     * 不套用 SAFE_ID 驗證:CacheWarmerService 於 @PostConstruct 呼叫此路徑,
     * 若因設定鍵不合規則拋出例外會讓應用程式無法啟動,而此路徑無不可信輸入需要防禦
     */
    @Override
    public String readSummary(String repoId) {
        Assert.hasText(repoId, "repoId must not be blank");
        return readFile(Path.of(workDir, "knowledge", "repos", repoId, "summary.md"));
    }

    private Path knowledgeRoot(String repoId) {
        return Path.of(workDir, "knowledge", "repos", requireSafeId(repoId, "repoId"));
    }

    /**
     * 驗證併入路徑的識別字,擋掉 ..、絕對路徑與隱藏檔
     *
     * 開頭限定 [a-z0-9] 是關鍵:僅檢查結尾片段擋不掉 ../x
     */
    private static String requireSafeId(String value, String field) {
        Assert.hasText(value, field + " must not be blank");
        if (!SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters: " + value);
        }
        return value;
    }

    /** 讀不到時回空字串,由 prompt 轉為「文件尚未建立」的使用者提示 */
    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            log.warn("Knowledge document not readable: {}", path, exception);
            return "";
        }
    }
}
