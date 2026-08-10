package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;

/**
 * 從目前候選或 provider follow-up 輸入投影受保護 execution 欄位的 capability 策略
 */
public interface CandidateBoundExecutionPlanner<P extends CandidateBoundPlanningInput, E> {

    boolean supportsDirectCandidate(AnalysisCandidate candidate);

    E planDirect(P input, AnalysisCandidate candidate);

    E planFollowUp(P input, E providerInput);
}
