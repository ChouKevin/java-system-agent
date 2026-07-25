package com.java.system.agent.runtime.application.answer;

import com.java.system.agent.runtime.domain.answer.Answer;
import com.java.system.agent.runtime.domain.answer.Claim;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.ClaimVerdict;
import com.java.system.agent.runtime.domain.answer.ClaimVerdictStatus;
import com.java.system.agent.runtime.domain.answer.CitableEvidence;
import com.java.system.agent.runtime.domain.answer.EvidenceHandle;
import com.java.system.agent.runtime.domain.answer.VerifiedClaim;
import com.java.system.agent.runtime.domain.need.Goal;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.port.out.AnswerDraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 依 Goal 要求的 need 覆蓋率，決定組合完成的 Answer 應該維持或降級收斂結果
 *
 * <p>由（尚未加入的）orchestrator 在 {@code AnswerCompositionPort} 產出 {@code AnswerDraft}、
 * {@code ClaimVerificationPort} 驗證每一項主張之後呼叫，是回答離開 kernel 前的最後一道判定：
 * 只要一個必要 need 仍有任一存活的 SUPPORTED 主張引用其證據，就算覆蓋，
 * 即使同一個 need 底下另有主張被拒絕——判斷依據是覆蓋率，不是個別主張的存廢</p>
 *
 * <p>被拒絕的主張不會從回傳的 {@link Answer} 移除，保留判定結果才能阻止下一輪組合
 * 重複主張同一件缺乏證據支持的事；主張引用未知的 {@link EvidenceHandle} 視為未引用，
 * 不視為錯誤，因為引用集合是驗證階段才核准的執行期資料，不是編譯期保證</p>
 */
public final class AnswerAcceptancePolicy {

    private AnswerAcceptancePolicy() {
    }

    public static AnswerAcceptance accept(
            Goal goal,
            AnswerDraft draft,
            List<ClaimVerdict> verdicts,
            List<CitableEvidence> citableEvidence,
            RunOutcome innerOutcome) {
        Objects.requireNonNull(goal, "goal must not be null");
        Objects.requireNonNull(draft, "answer draft must not be null");
        Objects.requireNonNull(verdicts, "claim verdicts must not be null");
        Objects.requireNonNull(citableEvidence, "citable evidence must not be null");
        Objects.requireNonNull(innerOutcome, "inner outcome must not be null");

        Map<EvidenceHandle, CitableEvidence> evidenceByHandle = citableEvidence.stream()
                .collect(Collectors.toMap(CitableEvidence::handle, Function.identity()));
        Map<ClaimId, ClaimVerdict> verdictsByClaimId = verdicts.stream()
                .collect(Collectors.toMap(ClaimVerdict::claimId, Function.identity()));

        List<VerifiedClaim> verifiedClaims = new ArrayList<>();
        Set<InformationNeedId> coveredNeedIds = new TreeSet<>();
        for (Claim claim : draft.claims()) {
            VerifiedClaim verifiedClaim = verify(claim, verdictsByClaimId);
            verifiedClaims.add(verifiedClaim);
            if (verifiedClaim.status() == ClaimVerdictStatus.SUPPORTED) {
                coveredNeedIds.addAll(coveredNeedIds(claim, evidenceByHandle));
            }
        }

        Set<InformationNeedId> uncoveredRequiredNeeds = new TreeSet<>(goal.requiredNeedIds());
        uncoveredRequiredNeeds.removeAll(coveredNeedIds);

        Answer answer = new Answer(draft.text(), verifiedClaims);
        RunOutcome outcome = resolveOutcome(innerOutcome, uncoveredRequiredNeeds.isEmpty());
        return new AnswerAcceptance(answer, outcome, uncoveredRequiredNeeds);
    }

    private static VerifiedClaim verify(Claim claim, Map<ClaimId, ClaimVerdict> verdictsByClaimId) {
        ClaimVerdict verdict = verdictsByClaimId.get(claim.id());
        if (Objects.isNull(verdict)) {
            return new VerifiedClaim(claim, ClaimVerdictStatus.UNSUPPORTED, "no claim verdict was returned");
        }
        return new VerifiedClaim(claim, verdict.status(), verdict.reason());
    }

    private static Set<InformationNeedId> coveredNeedIds(
            Claim claim,
            Map<EvidenceHandle, CitableEvidence> evidenceByHandle) {
        Set<InformationNeedId> coveredNeedIds = new TreeSet<>();
        for (EvidenceHandle handle : claim.citations()) {
            CitableEvidence citedEvidence = evidenceByHandle.get(handle);
            if (Objects.nonNull(citedEvidence)) {
                coveredNeedIds.add(citedEvidence.binding().informationNeedId());
            }
        }
        return coveredNeedIds;
    }

    private static RunOutcome resolveOutcome(RunOutcome innerOutcome, boolean allRequiredNeedsCovered) {
        if (innerOutcome != RunOutcome.COMPLETED) {
            return innerOutcome;
        }
        return allRequiredNeedsCovered ? RunOutcome.COMPLETED : RunOutcome.INCONCLUSIVE;
    }
}
