package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class RepositoryRevisionResultTest {

    @Test
    void readyContainsRevisionWithoutFailure() {
        RepositoryRevision revision = new RepositoryRevision("orders-42");

        RepositoryRevisionResult result = RepositoryRevisionResult.ready(revision);

        assertThat(result.revision()).contains(revision);
        assertThat(result.failure()).isEmpty();
    }

    @Test
    void unavailableContainsFailureWithoutInventedRevision() {
        SemanticFailure failure = new SemanticFailure(
                SemanticFailureCode.REPOSITORY_NOT_FOUND,
                "Repository does not exist",
                false);

        RepositoryRevisionResult result = RepositoryRevisionResult.unavailable(failure);

        assertThat(result.revision()).isEmpty();
        assertThat(result.failure()).contains(failure);
    }

    @Test
    void rejectsBothRevisionAndFailure() {
        RepositoryRevision revision = new RepositoryRevision("orders-42");
        SemanticFailure failure = new SemanticFailure(
                SemanticFailureCode.ENGINE_UNAVAILABLE,
                "Revision provider is unavailable",
                true);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RepositoryRevisionResult(
                        Optional.of(revision), Optional.of(failure)))
                .withMessageContaining("exactly one");
    }

    @Test
    void rejectsMissingRevisionAndFailure() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RepositoryRevisionResult(Optional.empty(), Optional.empty()))
                .withMessageContaining("exactly one");
    }

    @Test
    void rejectsNullRevisionOptional() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RepositoryRevisionResult(null, Optional.empty()))
                .withMessageContaining("revision");
    }

    @Test
    void rejectsNullFailureOptional() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RepositoryRevisionResult(Optional.empty(), null))
                .withMessageContaining("failure");
    }
}
