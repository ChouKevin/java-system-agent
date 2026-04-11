package com.java.system.agent.analysis.port;

import com.java.system.agent.analysis.exception.UnknownRepoException;

/** 讀取 repo 相關的 LLM 文件（service-map、business-map、skill doc、summary） */
public interface RepoDocPort {

    /** 讀取頂層 service-map，描述所有 repo 概覽 */
    String readServiceMap();

    /**
     * 讀取指定 repo 的 business-map（截斷至進入點對照表前）
     *
     * @throws UnknownRepoException 找不到 repoId 對應的 repo 時拋出
     */
    String readBusinessMap(String repoId);

    /**
     * 讀取指定 repo 的 skill 文件
     *
     * @throws UnknownRepoException 找不到 repoId 對應的 repo 時拋出
     */
    String readSkillDoc(String repoId, String groupName);

    /**
     * 讀取指定 repo 的 summary
     *
     * @throws UnknownRepoException 找不到 repoId 對應的 repo 時拋出
     */
    String readSummary(String repoId);
}
