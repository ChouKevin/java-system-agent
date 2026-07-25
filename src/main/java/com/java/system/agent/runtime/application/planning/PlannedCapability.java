package com.java.system.agent.runtime.application.planning;

import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link InformationNeedPlanner#plan} 在狀態為 {@code PLANNED} 時給出的具體規劃結果
 *
 * <p>把選定的語意能力、對應的 information need、目標 repository 與其預期 revision，
 * 以及（如果需要）已驗證的唯一語意目標打包在一起，供
 * {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 組出實際的語意查詢</p>
 */
public record PlannedCapability(
        SemanticCapability capability,
        InformationNeed informationNeed,
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        Optional<SemanticTarget> semanticTarget) {

    public PlannedCapability {
        Objects.requireNonNull(capability, "semantic capability must not be null");
        Objects.requireNonNull(informationNeed, "information need must not be null");
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Objects.requireNonNull(expectedRevision, "expected repository revision must not be null");
        Objects.requireNonNull(semanticTarget, "semantic target must not be null");
    }
}
