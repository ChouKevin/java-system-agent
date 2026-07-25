package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.need.InformationNeedType;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.scope.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RepositorySelection;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.SemanticQuery;
import com.java.system.agent.runtime.port.out.SemanticQueryResult;
import com.java.system.agent.runtime.port.out.SemanticResultStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemanticEvidenceValidatorTest {

    private static final RepositoryId REPOSITORY_ID = new RepositoryId("order-service");
    private static final RepositoryId OTHER_REPOSITORY_ID = new RepositoryId("notification-service");
    private static final RepositoryRevision REVISION = new RepositoryRevision("order-1");
    private static final InformationNeedId NEED_ID = new InformationNeedId("need-1");

    @Test
    @DisplayName("a query whose repository is not a candidate for the pending need is rejected")
    void rejectsAQueryNotBoundToTheCurrentState() {
        AttemptState state = state(pendingNeed());
        SemanticQuery query = query(pendingNeed(), OTHER_REPOSITORY_ID, REVISION);
        SemanticQueryResult result = successResult(evidence(REPOSITORY_ID));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SemanticEvidenceValidator.validate(state, pendingNeed(), query, result));

        assertEquals(
                "semantic query repository is not a candidate for the pending need",
                exception.getMessage());
    }

    @Test
    @DisplayName("a pending need that is not the exact registered value is rejected")
    void rejectsAPendingNeedThatIsNotTheExactOneSupplied() {
        AttemptState state = state(pendingNeed());
        InformationNeed changedNeed = new InformationNeed(
                NEED_ID,
                InformationNeedType.ENTRY_POINT,
                "a different question",
                true,
                List.of(REPOSITORY_ID),
                List.of());
        SemanticQuery query = query(changedNeed, REPOSITORY_ID, REVISION);
        SemanticQueryResult result = successResult(evidence(REPOSITORY_ID));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SemanticEvidenceValidator.validate(state, changedNeed, query, result));

        assertEquals(
                "information need is not the exact pending need value",
                exception.getMessage());
    }

    @Test
    @DisplayName("evidence bound to a different repository than the query is not accepted for the need")
    void rejectsEvidenceNotAcceptedForTheNeed() {
        AttemptState state = state(pendingNeed());
        SemanticQuery query = query(pendingNeed(), REPOSITORY_ID, REVISION);
        SemanticQueryResult result = successResult(evidence(OTHER_REPOSITORY_ID));

        EvidenceValidation validation = SemanticEvidenceValidator.validate(state, pendingNeed(), query, result);

        assertEquals(EvidenceValidationOutcome.PROTOCOL_ERROR, validation.outcome());
        assertThat(validation.validatedEvidence()).isEmpty();
    }

    @Test
    @DisplayName("new evidence bound to the query's repository and revision is accepted")
    void acceptsNewEvidenceForTheQueryRepository() {
        AttemptState state = state(pendingNeed());
        SemanticQuery query = query(pendingNeed(), REPOSITORY_ID, REVISION);
        EvidenceRef newEvidence = evidence(REPOSITORY_ID);
        SemanticQueryResult result = successResult(newEvidence);

        EvidenceValidation validation = SemanticEvidenceValidator.validate(state, pendingNeed(), query, result);

        assertEquals(EvidenceValidationOutcome.VALID, validation.outcome());
        assertThat(validation.validatedEvidence()).isPresent();
        assertThat(validation.validatedEvidence().orElseThrow().newEvidence()).containsExactly(newEvidence);
        assertThat(validation.validatedEvidence().orElseThrow().newDiscoveries()).isEmpty();
    }

    private AttemptState state(InformationNeed registeredPendingNeed) {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RepositoryScope scope = RepositoryScope.of(List.of(new RepositorySelection(
                REPOSITORY_ID, "evidence validator test scope", true, RepositoryDiscoverySource.USER)));
        RevisionVector revisionVector = RevisionVector.empty().pin(scope, REPOSITORY_ID, REVISION);
        SortedMap<InformationNeedId, InformationNeed> pendingNeeds = new TreeMap<>();
        pendingNeeds.put(registeredPendingNeed.id(), registeredPendingNeed);
        return new AttemptState(
                runId,
                attemptId,
                0,
                AttemptStatus.EXECUTING,
                scope,
                revisionVector,
                pendingNeeds,
                Set.of(),
                List.of(),
                List.of(),
                AttemptBudget.of(10, 5));
    }

    private InformationNeed pendingNeed() {
        return new InformationNeed(
                NEED_ID,
                InformationNeedType.ENTRY_POINT,
                "Resolve need-1",
                true,
                List.of(REPOSITORY_ID),
                List.of());
    }

    private SemanticQuery query(
            InformationNeed informationNeed,
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision) {
        return new SemanticQuery(
                "test-capability",
                informationNeed,
                target(),
                repositoryId,
                expectedRevision);
    }

    private SemanticQueryResult successResult(EvidenceRef... evidenceRefs) {
        return new SemanticQueryResult(
                SemanticResultStatus.SUCCESS,
                Optional.of(REVISION),
                List.of(evidenceRefs),
                List.of(),
                Optional.empty());
    }

    private EvidenceRef evidence(RepositoryId repositoryId) {
        return new EvidenceRef(
                "semantic",
                repositoryId,
                REVISION,
                target(),
                1.0,
                List.of(),
                new ArtifactRef("sha256:evidence-validator-test"));
    }

    private SemanticTarget target() {
        return new SemanticTarget(SemanticTargetKind.SYMBOL, "com.example.OrderController#create", Optional.empty());
    }
}
