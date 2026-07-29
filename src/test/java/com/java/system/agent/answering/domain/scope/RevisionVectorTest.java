package com.java.system.agent.answering.domain.scope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RevisionVectorTest {

    @Test
    void rejectsBlankRevision() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RepositoryRevision("  "));
    }

    @Test
    void pinsEachSelectedRepositoryOnce() {
        RevisionVector vector = RevisionVector.empty()
                .pin(new RepositoryId("payment-service"), new RepositoryRevision("pay-123"))
                .pin(new RepositoryId("order-service"), new RepositoryRevision("ord-456"));

        assertThat(vector.repositoryIds()).containsExactly(
                new RepositoryId("order-service"),
                new RepositoryId("payment-service"));
        assertThat(vector.matches(
                new RepositoryId("order-service"), new RepositoryRevision("ord-456"))).isTrue();
        assertThat(vector.matches(
                new RepositoryId("order-service"), new RepositoryRevision("other"))).isFalse();
    }

    @Test
    void rejectsReplacingPinnedRevision() {
        RevisionVector pinned = RevisionVector.empty().pin(
                new RepositoryId("order-service"),
                new RepositoryRevision("ord-456"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> pinned.pin(
                        new RepositoryId("order-service"),
                        new RepositoryRevision("ord-789")))
                .withMessageContaining("ord-456");
    }

    @Test
    void acceptsIdempotentPinAndAdditionalRepository() {
        RepositoryId orderService = new RepositoryId("order-service");
        RepositoryRevision orderRevision = new RepositoryRevision("ord-456");
        RevisionVector pinned = RevisionVector.empty().pin(orderService, orderRevision);

        RevisionVector repinned = pinned
                .pin(orderService, orderRevision)
                .pin(
                        new RepositoryId("payment-service"),
                        new RepositoryRevision("pay-123"));

        assertThat(repinned.repositoryIds()).containsExactly(orderService, new RepositoryId("payment-service"));
    }

    @Test
    void comparesPinnedRevisionsByValueRegardlessOfPinOrder() {
        RevisionVector firstVector = RevisionVector.empty()
                .pin(new RepositoryId("payment-service"), new RepositoryRevision("pay-123"))
                .pin(new RepositoryId("order-service"), new RepositoryRevision("ord-456"));
        RevisionVector equivalentVector = RevisionVector.empty()
                .pin(new RepositoryId("order-service"), new RepositoryRevision("ord-456"))
                .pin(new RepositoryId("payment-service"), new RepositoryRevision("pay-123"));
        RevisionVector differentRevision = RevisionVector.empty()
                .pin(new RepositoryId("payment-service"), new RepositoryRevision("pay-789"))
                .pin(new RepositoryId("order-service"), new RepositoryRevision("ord-456"));
        RevisionVector differentRepository = RevisionVector.empty()
                .pin(new RepositoryId("inventory-service"), new RepositoryRevision("inv-123"))
                .pin(new RepositoryId("order-service"), new RepositoryRevision("ord-456"));

        assertThat(firstVector).isEqualTo(equivalentVector);
        assertThat(firstVector.hashCode()).isEqualTo(equivalentVector.hashCode());
        assertThat(firstVector).isNotEqualTo(differentRevision);
        assertThat(firstVector).isNotEqualTo(differentRepository);
        assertThat(RevisionVector.empty()).isEqualTo(RevisionVector.empty());
    }

}
