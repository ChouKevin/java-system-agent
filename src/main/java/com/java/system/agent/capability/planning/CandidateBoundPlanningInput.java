package com.java.system.agent.capability.planning;

import java.util.List;

/**
 * 模型提交的候選授權規劃輸入，只包含候選 handle 與可安全覆寫的規劃欄位
 */
public interface CandidateBoundPlanningInput {

    List<String> candidateHandles();

    String questionToResolve();

    String rationale();
}
