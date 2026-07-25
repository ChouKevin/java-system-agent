package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.answer.CitableEvidence;
import com.java.system.agent.runtime.domain.answer.VerifiedClaim;
import com.java.system.agent.runtime.domain.run.AnalysisWarning;

import java.util.List;

/**
 * 把已收集的證據組合成一份回答草稿
 */
public interface AnswerCompositionPort {

    /**
     * 組合回答草稿
     *
     * @param question         使用者原始問題
     * @param citableEvidence  可供引用的證據
     * @param warnings         分析過程累積的警告
     * @param rejectedClaims   上一輪未被驗證通過的主張；首次組合時為空，
     *                         replan 時帶入上一輪的 unsupported 主張，
     *                         避免這一輪重複主張同一件缺乏證據支持的事
     */
    AnswerDraft compose(
            String question,
            List<CitableEvidence> citableEvidence,
            List<AnalysisWarning> warnings,
            List<VerifiedClaim> rejectedClaims);
}
