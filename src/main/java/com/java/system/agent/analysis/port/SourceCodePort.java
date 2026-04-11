package com.java.system.agent.analysis.port;

import com.java.system.agent.analysis.exception.UnknownRepoException;

import java.nio.file.Path;

public interface SourceCodePort {
    /**
     * 取得指定 repo 的原始碼根目錄
     *
     * @param repoId repo 識別碼
     * @return 原始碼根目錄路徑
     * @throws UnknownRepoException 找不到 repoId 對應的 repo 時拋出
     */
    Path sourceRoot(String repoId);
}
