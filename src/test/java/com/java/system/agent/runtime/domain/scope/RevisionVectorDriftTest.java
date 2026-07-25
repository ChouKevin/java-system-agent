package com.java.system.agent.runtime.domain.scope;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RevisionVectorDriftTest {

    @Test
    void reportsNoDriftForIdenticalVectors() {
        RepositoryScope scope = scope("order-service");
        RepositoryId orderService = new RepositoryId("order-service");
        RevisionVector previous = RevisionVector.empty()
                .pin(scope, orderService, new RepositoryRevision("ord-1"));
        RevisionVector current = RevisionVector.empty()
                .pin(scope, orderService, new RepositoryRevision("ord-1"));

        assertThat(current.driftedFrom(previous)).isEmpty();
    }

    @Test
    void reportsOnlyTheRepositoryThatMoved() {
        RepositoryScope scope = scope("order-service", "notification-service");
        RepositoryId orderService = new RepositoryId("order-service");
        RepositoryId notificationService = new RepositoryId("notification-service");
        RevisionVector previous = RevisionVector.empty()
                .pin(scope, orderService, new RepositoryRevision("a1"))
                .pin(scope, notificationService, new RepositoryRevision("b1"));
        RevisionVector current = RevisionVector.empty()
                .pin(scope, orderService, new RepositoryRevision("a2"))
                .pin(scope, notificationService, new RepositoryRevision("b1"));

        assertThat(current.driftedFrom(previous)).containsExactly(orderService);
    }

    @Test
    void ignoresARepositoryAbsentFromThePreviousVector() {
        RepositoryScope scope = scope("order-service", "notification-service");
        RepositoryId orderService = new RepositoryId("order-service");
        RepositoryId notificationService = new RepositoryId("notification-service");
        RevisionVector previous = RevisionVector.empty()
                .pin(scope, orderService, new RepositoryRevision("a1"));
        RevisionVector current = RevisionVector.empty()
                .pin(scope, orderService, new RepositoryRevision("a1"))
                .pin(scope, notificationService, new RepositoryRevision("b1"));

        assertThat(current.driftedFrom(previous)).isEmpty();
    }

    @Test
    void ignoresARepositoryAbsentFromTheCurrentVector() {
        RepositoryScope scope = scope("order-service", "notification-service");
        RepositoryId orderService = new RepositoryId("order-service");
        RepositoryId notificationService = new RepositoryId("notification-service");
        RevisionVector previous = RevisionVector.empty()
                .pin(scope, orderService, new RepositoryRevision("a1"))
                .pin(scope, notificationService, new RepositoryRevision("b1"));
        RevisionVector current = RevisionVector.empty()
                .pin(scope, orderService, new RepositoryRevision("a1"));

        assertThat(current.driftedFrom(previous)).isEmpty();
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
                "reason",
                true,
                RepositoryDiscoverySource.USER);
    }
}
