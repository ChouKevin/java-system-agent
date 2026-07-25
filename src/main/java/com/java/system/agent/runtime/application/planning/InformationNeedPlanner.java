package com.java.system.agent.runtime.application.planning;

import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 為目前待處理的 {@code InformationNeed} 選出一個可執行的語意能力與查詢前提
 *
 * <p>由 {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 在迴圈的
 * planning 階段呼叫，輸出決定下一步是要送出語意查詢，還是因為前提不足、能力缺漏或
 * 預算耗盡而終止 run</p>
 *
 * <p>能力選擇是純規則判斷——比對 {@link SemanticCapabilityRegistry} 依
 * information need 型別找出的候選數量與各項前提（scope、revision、語意目標唯一性）
 * 是否齊備，完全不呼叫任何 LLM，也不會做語意層面的猜測</p>
 */
public final class InformationNeedPlanner {

    public PlanningResult plan(
            AttemptState state,
            InformationNeed informationNeed,
            SemanticCapabilityRegistry registry) {
        Objects.requireNonNull(state, "analysis state must not be null");
        Objects.requireNonNull(informationNeed, "information need must not be null");
        Objects.requireNonNull(registry, "semantic capability registry must not be null");

        if (!state.budget().hasSemanticCallRemaining()) {
            return diagnosed(PlanningStatus.BUDGET_EXHAUSTED, "semantic call budget is exhausted");
        }
        List<SemanticCapability> capabilities = registry.matching(informationNeed.type());
        if (capabilities.size() < 1) {
            return diagnosed(
                    PlanningStatus.CAPABILITY_MISSING,
                    "no semantic capability supports " + informationNeed.type());
        }
        if (capabilities.size() > 1) {
            String candidates = capabilities.stream()
                    .map(SemanticCapability::qualifiedName)
                    .collect(Collectors.joining(", "));
            return diagnosed(
                    PlanningStatus.AMBIGUOUS_CAPABILITY,
                    "multiple semantic capabilities match: " + candidates);
        }

        SemanticCapability capability = capabilities.getFirst();
        if (informationNeed.repositoryCandidates().size() != 1) {
            return diagnosed(
                    PlanningStatus.PREREQUISITE_MISSING,
                    "planning requires exactly one validated repository candidate");
        }
        RepositoryId repositoryId = informationNeed.repositoryCandidates().getFirst();
        if (!state.repositoryScope().contains(repositoryId)) {
            return diagnosed(
                    PlanningStatus.PREREQUISITE_MISSING,
                    "repository candidate is not in the analysis scope");
        }
        Optional<RepositoryRevision> expectedRevision = state.revisionVector().revisionOf(repositoryId);
        if (capability.requiresPinnedRevision() && !expectedRevision.isPresent()) {
            return diagnosed(
                    PlanningStatus.PREREQUISITE_MISSING,
                    "repository candidate does not have a pinned revision");
        }
        Optional<SemanticTarget> semanticTarget = uniqueTarget(informationNeed, capability);
        if (capability.requiresSemanticTarget() && !semanticTarget.isPresent()) {
            return diagnosed(
                    PlanningStatus.PREREQUISITE_MISSING,
                    "semantic capability requires exactly one validated target");
        }
        if (!expectedRevision.isPresent()) {
            return diagnosed(
                    PlanningStatus.PREREQUISITE_MISSING,
                    "planned semantic query requires an expected revision");
        }
        PlannedCapability planned = new PlannedCapability(
                capability,
                informationNeed,
                repositoryId,
                expectedRevision.orElseThrow(),
                semanticTarget);
        return new PlanningResult(
                PlanningStatus.PLANNED,
                Optional.of(planned),
                "semantic capability selected deterministically");
    }

    private Optional<SemanticTarget> uniqueTarget(
            InformationNeed informationNeed,
            SemanticCapability capability) {
        if (!capability.requiresSemanticTarget() && informationNeed.targetHints().size() < 1) {
            return Optional.empty();
        }
        if (informationNeed.targetHints().size() != 1) {
            return Optional.empty();
        }
        return Optional.of(informationNeed.targetHints().getFirst());
    }

    private PlanningResult diagnosed(PlanningStatus status, String diagnosis) {
        return new PlanningResult(status, Optional.empty(), diagnosis);
    }
}
