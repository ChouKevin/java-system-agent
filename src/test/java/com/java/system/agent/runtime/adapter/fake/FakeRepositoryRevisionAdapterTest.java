package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryRevision;
import com.java.system.agent.analysis.port.out.RepositoryRevisionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class FakeRepositoryRevisionAdapterTest {

    @Test
    void returnsRegisteredResultsInOrderAndRepeatsTheLastResult() {
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevisionResult first = RepositoryRevisionResult.ready(new RepositoryRevision("r1"));
        RepositoryRevisionResult second = RepositoryRevisionResult.ready(new RepositoryRevision("r2"));
        FakeRepositoryRevisionAdapter adapter = new FakeRepositoryRevisionAdapter()
                .register(repositoryId, first, second);

        assertThat(adapter.currentRevision(repositoryId)).isSameAs(first);
        assertThat(adapter.currentRevision(repositoryId)).isSameAs(second);
        assertThat(adapter.currentRevision(repositoryId)).isSameAs(second);
    }

    @Test
    void missingRepositoryFailsClosed() {
        FakeRepositoryRevisionAdapter adapter = new FakeRepositoryRevisionAdapter();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.currentRevision(new RepositoryId("missing")))
                .withMessageContaining("scenario");
    }

    @Test
    void rejectsNullAndEmptyRegistrations() {
        FakeRepositoryRevisionAdapter adapter = new FakeRepositoryRevisionAdapter();
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevisionResult result = RepositoryRevisionResult.ready(new RepositoryRevision("r1"));

        assertThatNullPointerException()
                .isThrownBy(() -> adapter.register(null, result));
        assertThatNullPointerException()
                .isThrownBy(() -> adapter.register(repositoryId, (RepositoryRevisionResult[]) null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.register(repositoryId));
        assertThatNullPointerException()
                .isThrownBy(() -> adapter.register(repositoryId, result, null));
    }

    @Test
    void rejectsNullQueryId() {
        FakeRepositoryRevisionAdapter adapter = new FakeRepositoryRevisionAdapter();

        assertThatNullPointerException()
                .isThrownBy(() -> adapter.currentRevision(null));
    }
}
