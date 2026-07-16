package com.java.system.agent.analysis.port;

/** 讀取 repo 相關的 LLM 文件（service-map、business-map、業務群組文件、summary） */
public interface RepoDocPort {

    /** 讀取頂層 service-map，描述所有 repo 概覽 */
    String readServiceMap();

    /**
     * 讀取指定 repo 的 business-map（截斷至進入點對照表前）
     *
     * 文件不存在或無法讀取時回傳空字串
     *
     * @throws IllegalArgumentException repoId 未通過驗證時拋出
     */
    String readBusinessMap(String repoId);

    /**
     * 讀取指定 repo 的業務群組文件
     *
     * 文件不存在或無法讀取時回傳空字串
     *
     * @throws IllegalArgumentException repoId 或 groupName 未通過驗證時拋出
     */
    String readBusinessGroupDoc(String repoId, String groupName);

    /**
     * 讀取指定 repo 的 summary
     *
     * 文件不存在或無法讀取時回傳空字串
     * repoId 來源為啟動時的設定鍵而非 LLM 輸入,不驗證格式,僅不可為空白
     */
    String readSummary(String repoId);
}
