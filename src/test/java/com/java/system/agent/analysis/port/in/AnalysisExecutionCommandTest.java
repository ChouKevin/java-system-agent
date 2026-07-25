package com.java.system.agent.analysis.port.in;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.Goal;
import com.java.system.agent.analysis.domain.InformationNeed;
import com.java.system.agent.analysis.domain.InformationNeedId;
import com.java.system.agent.analysis.domain.InformationNeedType;
import com.java.system.agent.analysis.domain.RepositoryDiscoverySource;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryScope;
import com.java.system.agent.analysis.domain.RepositorySelection;
import com.java.system.agent.analysis.domain.SemanticTarget;
import com.java.system.agent.analysis.domain.SemanticTargetKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisExecutionCommandTest {

    @Test
    void rejectsNullRequiredFieldsAndNeedElements() {
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionCommand(
                null, attemptId(), scope(), List.of(need("need-a")), goal("need-a"), budget()));
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionCommand(
                runId(), null, scope(), List.of(need("need-a")), goal("need-a"), budget()));
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionCommand(
                runId(), attemptId(), null, List.of(need("need-a")), goal("need-a"), budget()));
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionCommand(
                runId(), attemptId(), scope(), null, goal("need-a"), budget()));
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionCommand(
                runId(), attemptId(), scope(), List.of(need("need-a")), null, budget()));
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionCommand(
                runId(), attemptId(), scope(), List.of(need("need-a")), goal("need-a"), null));

        List<InformationNeed> needsWithNullElement = new ArrayList<>();
        needsWithNullElement.add(null);

        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionCommand(
                runId(), attemptId(), scope(), needsWithNullElement, goal("need-a"), budget()));
    }

    @Test
    void rejectsAnEmptyScopeOrNeedList() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionCommand(
                runId(), attemptId(), RepositoryScope.of(List.of()), List.of(need("need-a")),
                goal("need-a"), budget()));
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionCommand(
                runId(), attemptId(), scope(), List.of(), new Goal("No needs", Set.of()), budget()));
    }

    @Test
    void rejectsDuplicateNeedIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionCommand(
                runId(), attemptId(), scope(), List.of(need("need-a"), need("need-a")),
                goal("need-a"), budget()));
    }

    @Test
    void rejectsGoalReferencesToUnknownNeeds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisExecutionCommand(
                runId(), attemptId(), scope(), List.of(need("need-a")), goal("need-b"), budget()));
    }

    @Test
    void sortsNeedsAndDefensivelyCopiesTheProvidedList() {
        InformationNeed first = need("need-a");
        InformationNeed second = need("need-b");
        List<InformationNeed> suppliedNeeds = new ArrayList<>(List.of(second, first));

        AnalysisExecutionCommand command = new AnalysisExecutionCommand(
                runId(), attemptId(), scope(), suppliedNeeds, new Goal("Both needs", Set.of(
                new InformationNeedId("need-a"), new InformationNeedId("need-b"))), budget());
        suppliedNeeds.clear();

        assertThat(command.informationNeeds()).containsExactly(first, second);
        assertThatThrownBy(() -> command.informationNeeds().add(first))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void retainsTheCallerProvidedFirstAttemptId() {
        AnalysisAttemptId firstAttemptId = new AnalysisAttemptId("caller-attempt");

        AnalysisExecutionCommand command = new AnalysisExecutionCommand(
                runId(), firstAttemptId, scope(), List.of(need("need-a")), goal("need-a"), budget());

        assertThat(command.firstAttemptId()).isSameAs(firstAttemptId);
    }

    @Test
    void allowsNeedCandidatesOutsideTheInitialScope() {
        InformationNeed crossRepositoryNeed = new InformationNeed(
                new InformationNeedId("need-cross-repository"),
                InformationNeedType.CROSS_SERVICE_TARGET,
                "Locate the downstream consumer",
                true,
                List.of(new RepositoryId("notification-service")),
                List.of(new SemanticTarget(
                        SemanticTargetKind.ENTRY_POINT,
                        "NotificationConsumer",
                        Optional.empty())));

        AnalysisExecutionCommand command = new AnalysisExecutionCommand(
                runId(), attemptId(), scope(), List.of(crossRepositoryNeed),
                goal("need-cross-repository"), budget());

        assertThat(command.informationNeeds()).containsExactly(crossRepositoryNeed);
    }

    private AnalysisRunId runId() {
        return new AnalysisRunId("run-1");
    }

    private AnalysisAttemptId attemptId() {
        return new AnalysisAttemptId("attempt-1");
    }

    private RepositoryScope scope() {
        return RepositoryScope.of(List.of(new RepositorySelection(
                new RepositoryId("order-service"),
                "Selected by the user",
                true,
                RepositoryDiscoverySource.USER)));
    }

    private InformationNeed need(String id) {
        return new InformationNeed(
                new InformationNeedId(id),
                InformationNeedType.ENTRY_POINT,
                "Find " + id,
                true,
                List.of(new RepositoryId("order-service")),
                List.of());
    }

    private Goal goal(String requiredNeedId) {
        return new Goal("Resolve " + requiredNeedId, Set.of(new InformationNeedId(requiredNeedId)));
    }

    private AnalysisBudget budget() {
        return AnalysisBudget.of(10, 5);
    }
}
