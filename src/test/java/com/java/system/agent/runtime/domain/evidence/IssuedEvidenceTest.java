package com.java.system.agent.runtime.domain.evidence;

import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class IssuedEvidenceTest {

    @Test
    void should_reject_evidence_when_its_repository_revision_is_not_in_the_handle_vector() {
        HandleBinding binding = new HandleBinding(
                new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), RevisionVector.empty());
        EvidenceHandle handle = new EvidenceHandle("evidence-1", binding);
        EvidenceRef evidence = new EvidenceRef("semantic", new RepositoryId("repo-1"), new RepositoryRevision("rev-1"),
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "Evidence", List.of(), new ArtifactRef("digest"));

        assertThatIllegalArgumentException().isThrownBy(() -> new IssuedEvidence(handle, evidence));
    }
}
