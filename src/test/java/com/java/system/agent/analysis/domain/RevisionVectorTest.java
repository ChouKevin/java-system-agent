package com.java.system.agent.analysis.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RevisionVectorTest {

    @Test
    void rejectsBlankRevision() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RepositoryRevision("  "));
    }

    @Test
    void pinsEachSelectedRepositoryOnce() {
        RepositoryScope scope = scope("payment-service", "order-service");

        RevisionVector vector = RevisionVector.empty()
                .pin(scope, new RepositoryId("payment-service"), new RepositoryRevision("pay-123"))
                .pin(scope, new RepositoryId("order-service"), new RepositoryRevision("ord-456"));

        assertThat(vector.repositoryIds()).containsExactly(
                new RepositoryId("order-service"),
                new RepositoryId("payment-service"));
        assertThat(vector.matches(
                new RepositoryId("order-service"), new RepositoryRevision("ord-456"))).isTrue();
        assertThat(vector.matches(
                new RepositoryId("order-service"), new RepositoryRevision("other"))).isFalse();
    }

    @Test
    void rejectsPinningRepositoryOutsideScope() {
        RepositoryScope scope = scope("order-service");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RevisionVector.empty().pin(
                        scope,
                        new RepositoryId("payment-service"),
                        new RepositoryRevision("pay-123")))
                .withMessageContaining("payment-service");
    }

    @Test
    void rejectsReplacingPinnedRevision() {
        RepositoryScope scope = scope("order-service");
        RevisionVector pinned = RevisionVector.empty().pin(
                scope,
                new RepositoryId("order-service"),
                new RepositoryRevision("ord-456"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> pinned.pin(
                        scope,
                        new RepositoryId("order-service"),
                        new RepositoryRevision("ord-789")))
                .withMessageContaining("ord-456");
    }

    @Test
    void acceptsIdempotentPinAndNewRepositoryAfterScopeExpansion() {
        RepositoryScope initial = scope("order-service");
        RepositoryId orderService = new RepositoryId("order-service");
        RepositoryRevision orderRevision = new RepositoryRevision("ord-456");
        RevisionVector pinned = RevisionVector.empty().pin(initial, orderService, orderRevision);

        RepositoryScope expanded = initial.expand(selection("payment-service"));
        RevisionVector repinned = pinned
                .pin(expanded, orderService, orderRevision)
                .pin(
                        expanded,
                        new RepositoryId("payment-service"),
                        new RepositoryRevision("pay-123"));

        assertThat(repinned.repositoryIds()).containsExactly(orderService, new RepositoryId("payment-service"));
    }

    private RepositoryScope scope(String... repositoryIds) {
        List<RepositorySelection> selections = Arrays.stream(repositoryIds)
                .map(this::selection)
                .toList();
        return RepositoryScope.of(selections);
    }

    private RepositorySelection selection(String repositoryId) {
        return new RepositorySelection(
                new RepositoryId(repositoryId),
                "Selected for test coverage",
                true,
                RepositoryDiscoverySource.USER);
    }
}
