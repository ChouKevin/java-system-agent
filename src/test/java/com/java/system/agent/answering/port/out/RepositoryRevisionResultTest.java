package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class RepositoryRevisionResultTest {

    @Test
    void readyContainsTheResolvedRevision() {
        RepositoryRevision revision = new RepositoryRevision("orders-42");

        RepositoryRevisionResult result = RepositoryRevisionResult.ready(revision);

        assertThat(result).isEqualTo(new RepositoryRevisionResult.Ready(revision));
    }

    @Test
    void failedContainsTheProviderNeutralFailure() {
        RepositoryRevisionFailure failure = new RepositoryRevisionFailure(
                RepositoryRevisionFailureCode.REPOSITORY_NOT_FOUND,
                "Repository does not exist",
                "revision-provider");

        RepositoryRevisionResult result = RepositoryRevisionResult.failed(failure);

        assertThat(result).isEqualTo(new RepositoryRevisionResult.Failed(failure));
    }

    @Test
    void failedRejectsMissingFailure() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RepositoryRevisionResult.Failed(null))
                .withMessageContaining("failure");
    }

    @Test
    void failureRejectsBlankDescription() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RepositoryRevisionFailure(
                        RepositoryRevisionFailureCode.TIMEOUT, " ", "revision-provider"))
                .withMessageContaining("description");
    }
}
