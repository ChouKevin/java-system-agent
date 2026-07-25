package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.answer.CitableEvidence;
import com.java.system.agent.runtime.domain.answer.VerifiedClaim;
import com.java.system.agent.runtime.domain.run.AnalysisWarning;
import com.java.system.agent.runtime.domain.scope.RepositoryId;

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

    /**
     * 組合一則向使用者澄清 repository scope 的回覆
     *
     * <p>只在沒有任何候選 repository 通過 catalog 驗證時才會被呼叫；
     * {@code rejectedCandidates} 是模型提出、卻不在 catalog 內的 repository ID</p>
     *
     * <p>回傳的草稿不帶任何 claim，其 {@code text} 與一般回答的 {@code AnswerDraft.text}
     * 一樣未經驗證——澄清語句本身不主張任何事實，沒有 claim 可供
     * {@code ClaimVerificationPort} 驗證</p>
     */
    AnswerDraft composeClarification(
            String question,
            List<RepositoryDescriptor> catalog,
            List<RepositoryId> rejectedCandidates);
}
