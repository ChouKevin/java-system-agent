package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.need.Goal;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record AnalysisExecutionCommand(
        AnalysisRunId runId,
        AnalysisAttemptId firstAttemptId,
        RepositoryScope initialScope,
        List<InformationNeed> informationNeeds,
        Goal goal,
        AttemptBudget attemptBudget) {

    public AnalysisExecutionCommand {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(firstAttemptId, "first analysis attempt ID must not be null");
        Objects.requireNonNull(initialScope, "initial repository scope must not be null");
        Objects.requireNonNull(informationNeeds, "information needs must not be null");
        Objects.requireNonNull(goal, "analysis goal must not be null");
        Objects.requireNonNull(attemptBudget, "analysis attempt budget must not be null");
        if (initialScope.selections().size() < 1) {
            throw new IllegalArgumentException("initial repository scope must not be empty");
        }
        informationNeeds = immutableSortedNeeds(informationNeeds);
        if (informationNeeds.size() < 1) {
            throw new IllegalArgumentException("information needs must not be empty");
        }
        validateNeedIds(informationNeeds, goal);
    }

    private static List<InformationNeed> immutableSortedNeeds(List<InformationNeed> informationNeeds) {
        return informationNeeds.stream()
                .map(need -> Objects.requireNonNull(need, "information need must not be null"))
                .sorted(Comparator.comparing(need -> need.id().value()))
                .toList();
    }

    private static void validateNeedIds(List<InformationNeed> informationNeeds, Goal goal) {
        Set<InformationNeedId> suppliedNeedIds = informationNeeds.stream()
                .map(InformationNeed::id)
                .collect(Collectors.toUnmodifiableSet());
        if (suppliedNeedIds.size() != informationNeeds.size()) {
            throw new IllegalArgumentException("information need IDs must be unique");
        }
        if (!suppliedNeedIds.containsAll(goal.requiredNeedIds())) {
            throw new IllegalArgumentException("goal requires information needs that were not supplied");
        }
    }
}
