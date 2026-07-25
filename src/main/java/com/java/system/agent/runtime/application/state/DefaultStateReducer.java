package com.java.system.agent.runtime.application.state;

import com.java.system.agent.runtime.application.lifecycle.RevisionMismatchException;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.run.AnalysisWarning;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.need.EvidenceBinding;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RepositorySelection;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 把一個 {@link AnalysisEvent} 套用到目前的 {@link AttemptState}，算出候選新狀態的唯一元件
 *
 * <p>是整個 runtime kernel 中唯一會依 {@link AnalysisEvent} 轉換既有 {@code AttemptState}
 * 的類別，其他所有協作者都只能經由 {@link TransitionCommitter} 間接呼叫這裡，不得自行
 * 轉換狀態；唯一的例外是 {@code AttemptState#initial}，它在 attempt 準備階段建立起始狀態，
 * 不經過這裡的 reduce-and-commit 流程</p>
 *
 * <p>這個類別刻意維持 330 行沒有拆分——把九種事件的所有不變量集中在同一個地方，是
 * 讓 reduce 能用窮盡 switch 保證完整性、讓 validateEnvelope 能一次守住所有樂觀鎖規則的
 * 前提；拆開會讓這些不變量散落各處，未來新增事件型別時更容易漏掉某個分支</p>
 */
public final class DefaultStateReducer implements StateReducer {

    /**
     * 對 {@code currentState} 套用 {@code event}，算出候選新狀態並包成
     * {@link StateTransition}
     *
     * <p>先呼叫 {@link #validateEnvelope} 完成樂觀鎖檢查，通過後用窮盡的 pattern switch
     * 依 {@link AnalysisEvent} 的九個 sealed 子型別分派到對應的 applyXxx 方法，switch
     * 沒有 default 分支——{@code AnalysisEvent} 是 sealed interface，未來新增事件型別時
     * 這裡會編譯失敗直到補上對應分支，這是刻意的編譯期完整性保證，不是遺漏</p>
     */
    @Override
    public StateTransition reduce(AttemptState currentState, AnalysisEvent event) {
        Objects.requireNonNull(currentState, "current analysis state must not be null");
        Objects.requireNonNull(event, "analysis event must not be null");
        validateEnvelope(currentState, event);

        AttemptState candidateState = switch (event) {
            case AnalysisEvent.ScopeResolved scopeResolved -> applyScopeResolved(
                    currentState, scopeResolved);
            case AnalysisEvent.ScopeExpanded scopeExpanded -> applyScopeExpanded(
                    currentState, scopeExpanded);
            case AnalysisEvent.RevisionPinned revisionPinned -> applyRevisionPinned(
                    currentState, revisionPinned);
            case AnalysisEvent.NeedRegistered needRegistered -> applyNeedRegistered(
                    currentState, needRegistered);
            case AnalysisEvent.EvidenceAccepted evidenceAccepted -> applyEvidenceAccepted(
                    currentState, evidenceAccepted);
            case AnalysisEvent.NeedResolved needResolved -> applyNeedResolved(
                    currentState, needResolved);
            case AnalysisEvent.WarningRecorded warningRecorded -> applyWarningRecorded(
                    currentState, warningRecorded);
            case AnalysisEvent.BudgetConsumed budgetConsumed -> applyBudgetConsumed(
                    currentState, budgetConsumed);
            case AnalysisEvent.AttemptConcluded attemptConcluded -> applyAttemptConcluded(
                    currentState, attemptConcluded);
        };
        return new StateTransition(event, candidateState);
    }

    /**
     * 樂觀並行控制的守門：判斷 {@code event} 是否真的能套用在 {@code currentState} 上
     *
     * <p>依序擋下四種情況：事件屬於另一個 run（run ID 不符）、事件屬於另一個 attempt
     * （attempt ID 不符）、{@code expectedStateRevision} 與目前 {@code stateRevision}
     * 不一致時丟出 {@link StaleStateRevisionException}，以及任何事件想套用在已經終結
     * （STALE/COMPLETED/INCONCLUSIVE/FAILED/CANCELLED）的 attempt 上</p>
     */
    private void validateEnvelope(AttemptState currentState, AnalysisEvent event) {
        if (!currentState.runId().equals(event.runId())) {
            throw new IllegalArgumentException("analysis event belongs to another run");
        }
        if (!currentState.attemptId().equals(event.attemptId())) {
            throw new IllegalArgumentException("analysis event belongs to another attempt");
        }
        if (currentState.stateRevision() != event.expectedStateRevision()) {
            throw new StaleStateRevisionException(
                    event.expectedStateRevision(), currentState.stateRevision());
        }
        if (isTerminal(currentState.status())) {
            throw new IllegalArgumentException("concluded analysis attempt cannot accept events");
        }
    }

    /**
     * 首次寫入 repository scope，並把狀態推進到 {@code REVISION_PINNING}
     *
     * <p>只允許在 scope 尚未被解析過時套用，重複套用會被拒絕</p>
     */
    private AttemptState applyScopeResolved(
            AttemptState state,
            AnalysisEvent.ScopeResolved event) {
        if (state.repositoryScope().repositoryIds().size() > 0) {
            throw new IllegalArgumentException("repository scope is already resolved");
        }
        return next(
                state,
                AttemptStatus.REVISION_PINNING,
                event.repositoryScope(),
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    /**
     * 把新發現的 repository 加入 repository scope，不改變 status
     *
     * <p>只接受由先前已被接受的語意證據觸發的擴張，並且該證據的 revision 必須與目前
     * revision 向量相符，否則丟出 {@link RevisionMismatchException}</p>
     */
    private AttemptState applyScopeExpanded(
            AttemptState state,
            AnalysisEvent.ScopeExpanded event) {
        RepositoryDiscovery discovery = event.repositoryDiscovery();
        EvidenceRef sourceEvidence = discovery.sourceEvidence();
        boolean evidenceAccepted = state.evidenceBindings().stream()
                .anyMatch(binding -> binding.evidenceRef().equals(sourceEvidence));
        if (!evidenceAccepted) {
            throw new IllegalArgumentException(
                    "scope expansion requires previously accepted semantic evidence");
        }
        if (!state.revisionVector().matches(
                sourceEvidence.repositoryId(), sourceEvidence.repositoryRevision())) {
            throw new RevisionMismatchException(
                    sourceEvidence.repositoryId(), sourceEvidence.repositoryRevision());
        }
        RepositorySelection selection = new RepositorySelection(
                discovery.repositoryId(),
                discovery.discoveryReason(),
                event.required(),
                RepositoryDiscoverySource.SEMANTIC_EVIDENCE);
        RepositoryScope expandedScope = state.repositoryScope().expand(selection);
        return next(
                state,
                state.status(),
                expandedScope,
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    /**
     * 把指定 repository 的 revision 釘進 revision 向量，不改變 status
     */
    private AttemptState applyRevisionPinned(
            AttemptState state,
            AnalysisEvent.RevisionPinned event) {
        RevisionVector pinnedVector = state.revisionVector().pin(
                state.repositoryScope(), event.repositoryId(), event.repositoryRevision());
        return next(
                state,
                state.status(),
                state.repositoryScope(),
                pinnedVector,
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    /**
     * 把新的 information need 加入 pending 集合，並把狀態推進到 {@code PLANNING}
     *
     * <p>同一個 information need 只能註冊一次，重複註冊或註冊一個已解決過的 ID 都會被拒絕</p>
     */
    private AttemptState applyNeedRegistered(
            AttemptState state,
            AnalysisEvent.NeedRegistered event) {
        SortedMap<InformationNeedId, InformationNeed> pendingNeeds = new TreeMap<>(state.pendingNeeds());
        InformationNeed previous = pendingNeeds.putIfAbsent(
                event.informationNeed().id(), event.informationNeed());
        if (Objects.nonNull(previous) || state.resolvedNeedIds().contains(event.informationNeed().id())) {
            throw new IllegalArgumentException(
                    "information need is already registered: " + event.informationNeed().id().value());
        }
        return next(
                state,
                AttemptStatus.PLANNING,
                state.repositoryScope(),
                state.revisionVector(),
                pendingNeeds,
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    /**
     * 為某個 pending information need 新增一筆證據綁定，並把狀態推進到 {@code EXECUTING}
     *
     * <p>要求證據所在的 repository 必須是該 need 的候選、必須落在目前 scope 內，且證據
     * 的 revision 必須與釘選的 revision 相符（否則丟出
     * {@link RevisionMismatchException}），同一筆綁定也不能重複接受</p>
     */
    private AttemptState applyEvidenceAccepted(
            AttemptState state,
            AnalysisEvent.EvidenceAccepted event) {
        InformationNeed informationNeed = requirePendingNeed(state, event.informationNeedId());
        EvidenceRef evidenceRef = event.evidenceRef();
        RepositoryId repositoryId = evidenceRef.repositoryId();
        if (!informationNeed.repositoryCandidates().contains(repositoryId)) {
            throw new IllegalArgumentException(
                    "evidence repository is not a candidate for the information need");
        }
        if (!state.repositoryScope().contains(repositoryId)) {
            throw new IllegalArgumentException("evidence repository is outside the analysis scope");
        }
        if (!state.revisionVector().matches(repositoryId, evidenceRef.repositoryRevision())) {
            throw new RevisionMismatchException(repositoryId, evidenceRef.repositoryRevision());
        }
        EvidenceBinding binding = new EvidenceBinding(event.informationNeedId(), evidenceRef);
        if (state.evidenceBindings().contains(binding)) {
            throw new IllegalArgumentException("evidence is already accepted for this information need");
        }
        List<EvidenceBinding> bindings = new ArrayList<>(state.evidenceBindings());
        bindings.add(binding);
        return next(
                state,
                AttemptStatus.EXECUTING,
                state.repositoryScope(),
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                bindings,
                state.warnings(),
                state.budget());
    }

    /**
     * 把一個已有證據的 information need 從 pending 移到 resolved，並把狀態退回
     * {@code PLANNING} 準備規劃下一個 need
     *
     * <p>該 need 必須先有至少一筆已接受的證據，否則拒絕解決</p>
     */
    private AttemptState applyNeedResolved(
            AttemptState state,
            AnalysisEvent.NeedResolved event) {
        requirePendingNeed(state, event.informationNeedId());
        boolean hasEvidence = state.evidenceBindings().stream()
                .anyMatch(binding -> binding.informationNeedId().equals(event.informationNeedId()));
        if (!hasEvidence) {
            throw new IllegalArgumentException("information need cannot resolve without accepted evidence");
        }
        SortedMap<InformationNeedId, InformationNeed> pendingNeeds = new TreeMap<>(state.pendingNeeds());
        pendingNeeds.remove(event.informationNeedId());
        Set<InformationNeedId> resolvedNeedIds = new TreeSet<>(state.resolvedNeedIds());
        resolvedNeedIds.add(event.informationNeedId());
        return next(
                state,
                AttemptStatus.PLANNING,
                state.repositoryScope(),
                state.revisionVector(),
                pendingNeeds,
                resolvedNeedIds,
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    /**
     * 在 warnings 清單追加一筆警告，不改變 status
     */
    private AttemptState applyWarningRecorded(
            AttemptState state,
            AnalysisEvent.WarningRecorded event) {
        List<AnalysisWarning> warnings = new ArrayList<>(state.warnings());
        warnings.add(event.warning());
        return next(
                state,
                state.status(),
                state.repositoryScope(),
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                warnings,
                state.budget());
    }

    /**
     * 扣掉一次 step 預算，並在 {@code activity} 需要語意呼叫時額外扣掉一次語意呼叫預算、
     * 把狀態推進到 {@code EXECUTING}
     *
     * <p>是否消耗語意呼叫預算與是否推進狀態，兩者都完全由
     * {@link BudgetedActivity#consumesSemanticCall()} 決定</p>
     */
    private AttemptState applyBudgetConsumed(
            AttemptState state,
            AnalysisEvent.BudgetConsumed event) {
        AttemptBudget consumedBudget = state.budget().consumeStep();
        if (event.activity().consumesSemanticCall()) {
            consumedBudget = consumedBudget.consumeSemanticCall();
        }
        AttemptStatus status = event.activity().consumesSemanticCall()
                ? AttemptStatus.EXECUTING
                : state.status();
        return next(
                state,
                status,
                state.repositoryScope(),
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                consumedBudget);
    }

    /**
     * 把狀態推進到 {@code event.outcome()} 對應的終結 status，是狀態機到達終點的唯一寫入點
     */
    private AttemptState applyAttemptConcluded(
            AttemptState state,
            AnalysisEvent.AttemptConcluded event) {
        return next(
                state,
                terminalStatus(event.outcome()),
                state.repositoryScope(),
                state.revisionVector(),
                state.pendingNeeds(),
                state.resolvedNeedIds(),
                state.evidenceBindings(),
                state.warnings(),
                state.budget());
    }

    private InformationNeed requirePendingNeed(
            AttemptState state,
            InformationNeedId informationNeedId) {
        InformationNeed informationNeed = state.pendingNeeds().get(informationNeedId);
        if (Objects.isNull(informationNeed)) {
            throw new IllegalArgumentException(
                    "information need is not pending: " + informationNeedId.value());
        }
        return informationNeed;
    }

    private AttemptStatus terminalStatus(AttemptOutcome outcome) {
        return switch (outcome) {
            case COMPLETED -> AttemptStatus.COMPLETED;
            case STALE -> AttemptStatus.STALE;
            case INCONCLUSIVE -> AttemptStatus.INCONCLUSIVE;
            case FAILED -> AttemptStatus.FAILED;
            case CANCELLED -> AttemptStatus.CANCELLED;
        };
    }

    private boolean isTerminal(AttemptStatus status) {
        return switch (status) {
            case STALE, COMPLETED, INCONCLUSIVE, FAILED, CANCELLED -> true;
            default -> false;
        };
    }

    /**
     * 整個 runtime tree 裡唯一遞增 {@code stateRevision} 的地方，並在此組出新的
     * {@link AttemptState}
     *
     * <p>每個 applyXxx 方法算完各自要變動的欄位後，都要把結果交給這裡組裝——revision
     * 的遞增因此是結構上保證只發生一次，而不是靠九個 applyXxx 作者各自記得要加一的約定，
     * 這正是樂觀鎖能夠成立的原因</p>
     */
    private AttemptState next(
            AttemptState current,
            AttemptStatus status,
            RepositoryScope repositoryScope,
            RevisionVector revisionVector,
            Map<InformationNeedId, InformationNeed> pendingNeeds,
            Set<InformationNeedId> resolvedNeedIds,
            List<EvidenceBinding> evidenceBindings,
            List<AnalysisWarning> warnings,
            AttemptBudget budget) {
        return new AttemptState(
                current.runId(),
                current.attemptId(),
                current.stateRevision() + 1,
                status,
                repositoryScope,
                revisionVector,
                new TreeMap<>(pendingNeeds),
                resolvedNeedIds,
                evidenceBindings,
                warnings,
                budget);
    }
}
