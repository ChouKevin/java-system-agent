package com.java.system.agent.runtime.domain.scope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RevisionVectorDriftTest {

    @Test
    void reportsNoDriftForIdenticalVectors() {
        RepositoryId orderService = new RepositoryId("order-service");
        RevisionVector previous = RevisionVector.empty()
                .pin(orderService, new RepositoryRevision("ord-1"));
        RevisionVector current = RevisionVector.empty()
                .pin(orderService, new RepositoryRevision("ord-1"));

        assertThat(current.driftedFrom(previous)).isEmpty();
    }

    @Test
    void reportsOnlyTheRepositoryThatMoved() {
        RepositoryId orderService = new RepositoryId("order-service");
        RepositoryId notificationService = new RepositoryId("notification-service");
        RevisionVector previous = RevisionVector.empty()
                .pin(orderService, new RepositoryRevision("a1"))
                .pin(notificationService, new RepositoryRevision("b1"));
        RevisionVector current = RevisionVector.empty()
                .pin(orderService, new RepositoryRevision("a2"))
                .pin(notificationService, new RepositoryRevision("b1"));

        assertThat(current.driftedFrom(previous)).containsExactly(orderService);
    }

    @Test
    void ignoresARepositoryAbsentFromThePreviousVector() {
        RepositoryId orderService = new RepositoryId("order-service");
        RepositoryId notificationService = new RepositoryId("notification-service");
        RevisionVector previous = RevisionVector.empty()
                .pin(orderService, new RepositoryRevision("a1"));
        RevisionVector current = RevisionVector.empty()
                .pin(orderService, new RepositoryRevision("a1"))
                .pin(notificationService, new RepositoryRevision("b1"));

        assertThat(current.driftedFrom(previous)).isEmpty();
    }

    @Test
    void ignoresARepositoryAbsentFromTheCurrentVector() {
        RepositoryId orderService = new RepositoryId("order-service");
        RepositoryId notificationService = new RepositoryId("notification-service");
        RevisionVector previous = RevisionVector.empty()
                .pin(orderService, new RepositoryRevision("a1"))
                .pin(notificationService, new RepositoryRevision("b1"));
        RevisionVector current = RevisionVector.empty()
                .pin(orderService, new RepositoryRevision("a1"));

        assertThat(current.driftedFrom(previous)).isEmpty();
    }

}
