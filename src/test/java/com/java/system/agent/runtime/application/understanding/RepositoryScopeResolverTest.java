package com.java.system.agent.runtime.application.understanding;

import com.java.system.agent.runtime.domain.scope.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.port.out.QuestionUnderstanding;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryScopeResolverTest {

    @Test
    void buildsAScopeWithEveryCandidateFoundInTheCatalog() {
        QuestionUnderstanding understanding = new QuestionUnderstanding(
                List.of(new RepositoryId("order-service"), new RepositoryId("payment-service")),
                List.of());
        List<RepositoryDescriptor> catalog = List.of(
                new RepositoryDescriptor(new RepositoryId("order-service"), "Handles order placement"),
                new RepositoryDescriptor(new RepositoryId("payment-service"), "Handles payment charges"));

        Optional<RepositoryScope> resolved = RepositoryScopeResolver.resolve(understanding, catalog);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().repositoryIds()).containsExactlyInAnyOrder(
                new RepositoryId("order-service"), new RepositoryId("payment-service"));
        assertThat(resolved.orElseThrow().selections())
                .extracting(selection -> selection.discoverySource())
                .containsOnly(RepositoryDiscoverySource.QUESTION_UNDERSTANDING);
    }

    @Test
    void dropsACandidateThatIsAbsentFromTheCatalog() {
        QuestionUnderstanding understanding = new QuestionUnderstanding(
                List.of(new RepositoryId("order-service"), new RepositoryId("ghost-service")),
                List.of());
        List<RepositoryDescriptor> catalog = List.of(
                new RepositoryDescriptor(new RepositoryId("order-service"), "Handles order placement"));

        Optional<RepositoryScope> resolved = RepositoryScopeResolver.resolve(understanding, catalog);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().repositoryIds()).containsExactly(new RepositoryId("order-service"));
    }

    @Test
    void returnsEmptyWhenNoCandidateIsInTheCatalog() {
        QuestionUnderstanding understanding = new QuestionUnderstanding(
                List.of(new RepositoryId("ghost-service")),
                List.of());
        List<RepositoryDescriptor> catalog = List.of(
                new RepositoryDescriptor(new RepositoryId("order-service"), "Handles order placement"));

        Optional<RepositoryScope> resolved = RepositoryScopeResolver.resolve(understanding, catalog);

        assertThat(resolved).isEmpty();
    }
}
