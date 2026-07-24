package com.java.system.agent.analysis.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RepositoryScopeTest {

    @Test
    void rejectsBlankRepositoryIdentity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RepositoryId("  "));
    }

    @Test
    void keepsRequiredAndOptionalRepositoriesInStableOrder() {
        RepositorySelection optional = new RepositorySelection(
                new RepositoryId("payment-service"),
                "Payment may participate in the flow",
                false,
                RepositoryDiscoverySource.QUESTION_UNDERSTANDING);
        RepositorySelection required = new RepositorySelection(
                new RepositoryId("order-service"),
                "The user named the order flow",
                true,
                RepositoryDiscoverySource.USER);

        RepositoryScope scope = RepositoryScope.of(List.of(optional, required));

        assertThat(scope.selections()).containsExactly(required, optional);
        assertThat(scope.requiredRepositoryIds()).containsExactly(new RepositoryId("order-service"));
    }

    @Test
    void rejectsDuplicateRepositorySelection() {
        RepositorySelection first = selection("order-service", RepositoryDiscoverySource.USER);
        RepositorySelection duplicate = selection(
                "order-service", RepositoryDiscoverySource.REPOSITORY_METADATA);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RepositoryScope.of(List.of(first, duplicate)))
                .withMessageContaining("order-service");
    }

    @Test
    void expandsScopeWithDiscoveryReasonAndSource() {
        RepositoryScope initial = RepositoryScope.of(List.of(
                selection("order-service", RepositoryDiscoverySource.USER)));
        RepositorySelection discovered = new RepositorySelection(
                new RepositoryId("notification-service"),
                "OrderCreated consumer was discovered from semantic evidence",
                true,
                RepositoryDiscoverySource.SEMANTIC_EVIDENCE);

        RepositoryScope expanded = initial.expand(discovered);

        assertThat(initial.repositoryIds()).containsExactly(new RepositoryId("order-service"));
        assertThat(expanded.repositoryIds()).containsExactly(
                new RepositoryId("notification-service"),
                new RepositoryId("order-service"));
        assertThat(expanded.selection(new RepositoryId("notification-service")))
                .contains(discovered);
    }

    @Test
    void treatsIndependentlyReconstructedSelectionsAsEqualValueScopes() {
        RepositoryScope first = RepositoryScope.of(List.of(
                selection("order-service", RepositoryDiscoverySource.USER),
                selection("notification-service", RepositoryDiscoverySource.SEMANTIC_EVIDENCE)));
        RepositoryScope second = RepositoryScope.of(List.of(
                selection("notification-service", RepositoryDiscoverySource.SEMANTIC_EVIDENCE),
                selection("order-service", RepositoryDiscoverySource.USER)));

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void preservesValueEqualityWhenEquivalentScopesAreExpandedIndependently() {
        RepositoryScope first = RepositoryScope.of(List.of(
                selection("order-service", RepositoryDiscoverySource.USER)))
                .expand(selection("notification-service", RepositoryDiscoverySource.SEMANTIC_EVIDENCE));
        RepositoryScope second = RepositoryScope.of(List.of(
                selection("order-service", RepositoryDiscoverySource.USER)))
                .expand(selection("notification-service", RepositoryDiscoverySource.SEMANTIC_EVIDENCE));

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
    }

    private RepositorySelection selection(String repositoryId, RepositoryDiscoverySource source) {
        return new RepositorySelection(
                new RepositoryId(repositoryId),
                "Selected for test coverage",
                true,
                source);
    }
}
