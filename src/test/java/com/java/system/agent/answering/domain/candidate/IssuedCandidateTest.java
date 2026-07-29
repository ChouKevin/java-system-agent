package com.java.system.agent.answering.domain.candidate;

import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class IssuedCandidateTest {

    @Test
    void should_reject_candidate_whose_kind_differs_from_its_issued_handle() {
        HandleBinding binding = new HandleBinding(
                new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), RevisionVector.empty());
        CandidateHandle handle = new CandidateHandle("candidate-1", binding, CandidateKind.ROUTE);

        assertThatIllegalArgumentException().isThrownBy(() -> new IssuedCandidate(
                handle, new RepositoryCandidate(new RepositoryId("repo-1"), "Repository")));
    }
}
