package com.java.system.agent.git.adapter;

import com.java.system.agent.analysis.port.RepoDocPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
class RepoDocAdapter implements RepoDocPort {

    private static final String ENTRY_POINT_TABLE_MARKER = "\n## 進入點快速對照表";

    private final String workDir;

    RepoDocAdapter() {
        this(".");
    }

    /** 測試用：指定工作目錄。 */
    RepoDocAdapter(String workDir) {
        this.workDir = workDir;
    }

    @Override
    public String readServiceMap() {
        Path path = Path.of(workDir, "repos", "service-map.md");
        return readFile(path);
    }

    @Override
    public String readBusinessMap(String repoId) {
        Assert.hasText(repoId, "repoId must not be blank");
        Path path = Path.of(workDir, "repos", repoId, "docs", "business-map.md");
        String content = readFile(path);
        if (!StringUtils.hasText(content)) {
            return content;
        }
        int cutIndex = content.indexOf(ENTRY_POINT_TABLE_MARKER);
        return cutIndex >= 0 ? content.substring(0, cutIndex) : content;
    }

    @Override
    public String readSkillDoc(String repoId, String groupName) {
        Assert.hasText(repoId, "repoId must not be blank");
        if (!StringUtils.hasText(groupName)) {
            log.warn("readSkillDoc called with blank groupName for repo {}", repoId);
            return "";
        }
        Path path = Path.of(workDir, "repos", repoId, "docs", "skills", groupName + ".md");
        return readFile(path);
    }

    @Override
    public String readSummary(String repoId) {
        Assert.hasText(repoId, "repoId must not be blank");
        Path path = Path.of(workDir, "repos", repoId, "docs", "summary.md");
        return readFile(path);
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.warn("Failed to read doc file {}: {}", path, e.getMessage());
            return "";
        }
    }
}
