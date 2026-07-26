package com.java.system.agent.runtime.domain.handle;

import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HandleBindingTest {

    @Test
    void bindsOpaqueHandlesToTheExactRunAttemptAndRevisionVector() {
        RepositoryId repositoryId = new RepositoryId("orders");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, new RepositoryRevision("rev-1"));
        HandleBinding binding = new HandleBinding(
                new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions);

        CapabilityHandle handle = new CapabilityHandle("cap-1", binding);

        assertThat(handle.binding()).isEqualTo(binding);
        assertThat(handle.value()).isEqualTo("cap-1");
    }

    @Test
    void rejectsBlankOpaqueHandleValues() {
        HandleBinding binding = new HandleBinding(
                new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), RevisionVector.empty());

        assertThatIllegalArgumentException().isThrownBy(() -> new EvidenceHandle("  ", binding));
    }
}
