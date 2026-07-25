package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.need.EvidenceBinding;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;
import com.java.system.agent.runtime.port.out.SemanticQuery;
import com.java.system.agent.runtime.port.out.SemanticQueryResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 語意查詢的 pending need／revision 綁定是否成立，以及回傳的證據與 discovery 是否可信的檢查
 *
 * <p>由 {@link SemanticResultInterpreter} 在每次處理語意結果之前呼叫，確保查詢仍綁定在
 * 目前的 attempt 狀態上，且回傳的證據與 discovery 沒有違反 revision-bound 契約——
 * 過期的證據回報為 {@code STALE}，違反契約的回報為 {@code PROTOCOL_ERROR}，
 * 兩者都不會拋例外，只有 pending need／查詢綁定本身不成立時才會拋
 * {@code IllegalArgumentException}</p>
 */
final class SemanticEvidenceValidator {

    private SemanticEvidenceValidator() {
    }

    public static EvidenceValidation validate(
            AttemptState state,
            InformationNeed pendingNeed,
            SemanticQuery query,
            SemanticQueryResult result) {
        requireExactPendingNeed(state, pendingNeed);
        requireQueryBoundToState(state, query);
        return validateEvidenceAndDiscoveries(state, query, result);
    }

    private static EvidenceValidation validateEvidenceAndDiscoveries(
            AttemptState state,
            SemanticQuery query,
            SemanticQueryResult result) {
        InformationNeed pendingNeed = query.informationNeed();
        Optional<RepositoryRevision> analyzedRevision = result.analyzedRevision();
        if (analyzedRevision.isEmpty()) {
            return EvidenceValidation.protocolError();
        }
        boolean revisionMismatch = !analyzedRevision.orElseThrow().equals(query.expectedRevision());
        Set<EvidenceRef> returnedEvidence = new LinkedHashSet<>(result.evidence());
        List<EvidenceRef> newEvidence = new ArrayList<>();
        for (EvidenceRef evidence : result.evidence()) {
            if (!evidence.repositoryRevision().equals(analyzedRevision.orElseThrow())
                    || !evidence.repositoryId().equals(query.repositoryId())
                    || !pendingNeed.repositoryCandidates().contains(evidence.repositoryId())
                    || !state.repositoryScope().contains(evidence.repositoryId())) {
                return EvidenceValidation.protocolError();
            }
            if (!state.revisionVector().matches(evidence.repositoryId(), evidence.repositoryRevision())) {
                revisionMismatch = true;
            }
            if (!isAcceptedForNeed(state, pendingNeed, evidence) && !newEvidence.contains(evidence)) {
                newEvidence.add(evidence);
            }
        }
        Set<EvidenceRef> acceptedOrNewEvidence = new LinkedHashSet<>(newEvidence);
        for (EvidenceBinding binding : state.evidenceBindings()) {
            if (binding.informationNeedId().equals(pendingNeed.id())) {
                acceptedOrNewEvidence.add(binding.evidenceRef());
            }
        }
        List<RepositoryDiscovery> newDiscoveries = new ArrayList<>();
        Set<RepositoryId> discoveredRepositories = new LinkedHashSet<>();
        for (RepositoryDiscovery discovery : result.repositoryDiscoveries()) {
            if (!returnedEvidence.contains(discovery.sourceEvidence())
                    || !acceptedOrNewEvidence.contains(discovery.sourceEvidence())) {
                return EvidenceValidation.protocolError();
            }
            if (!state.repositoryScope().contains(discovery.repositoryId())
                    && discoveredRepositories.add(discovery.repositoryId())) {
                newDiscoveries.add(discovery);
            }
        }
        if (revisionMismatch) {
            return EvidenceValidation.stale();
        }
        return EvidenceValidation.valid(new ValidatedEvidence(newEvidence, newDiscoveries));
    }

    private static boolean isAcceptedForNeed(
            AttemptState state,
            InformationNeed pendingNeed,
            EvidenceRef evidence) {
        return state.evidenceBindings().contains(new EvidenceBinding(pendingNeed.id(), evidence));
    }

    private static void requireExactPendingNeed(AttemptState state, InformationNeed pendingNeed) {
        Optional<InformationNeed> registeredNeed = Optional.ofNullable(state.pendingNeeds().get(pendingNeed.id()));
        if (registeredNeed.filter(pendingNeed::equals).isEmpty()) {
            throw new IllegalArgumentException("information need is not the exact pending need value");
        }
    }

    private static void requireQueryBoundToState(AttemptState state, SemanticQuery query) {
        if (!query.informationNeed().repositoryCandidates().contains(query.repositoryId())) {
            throw new IllegalArgumentException(
                    "semantic query repository is not a candidate for the pending need");
        }
        if (!state.repositoryScope().contains(query.repositoryId())) {
            throw new IllegalArgumentException(
                    "semantic query repository is outside the analysis scope");
        }
        if (!state.revisionVector().matches(query.repositoryId(), query.expectedRevision())) {
            throw new IllegalArgumentException(
                    "semantic query expected revision is not pinned in the analysis state");
        }
    }
}
