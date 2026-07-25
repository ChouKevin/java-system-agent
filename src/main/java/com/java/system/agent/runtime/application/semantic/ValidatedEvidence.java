package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;

import java.util.List;

/**
 * {@link SemanticEvidenceValidator} 判定通過後，尚未提交的新證據與新發現的 repository
 *
 * <p>由 {@link SemanticResultInterpreter} 逐一提交為
 * {@code EvidenceAccepted}／{@code ScopeExpanded} 事件</p>
 */
record ValidatedEvidence(
        List<EvidenceRef> newEvidence,
        List<RepositoryDiscovery> newDiscoveries) {

    ValidatedEvidence {
        newEvidence = List.copyOf(newEvidence);
        newDiscoveries = List.copyOf(newDiscoveries);
    }
}
