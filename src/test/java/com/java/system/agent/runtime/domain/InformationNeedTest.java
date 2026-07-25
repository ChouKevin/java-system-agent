package com.java.system.agent.runtime.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InformationNeedTest {

    @Test
    void rejectsBlankNeedIdentity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new InformationNeedId("  "));
    }

    @Test
    void carriesOnlyTypedTargetsAndRepositoryCandidates() {
        SemanticTarget route = new SemanticTarget(
                SemanticTargetKind.ROUTE,
                "POST /orders",
                Optional.empty());
        InformationNeed need = new InformationNeed(
                new InformationNeedId("need-entry-point"),
                InformationNeedType.ENTRY_POINT,
                "Locate the order creation entry point",
                true,
                List.of(new RepositoryId("order-service")),
                List.of(route));

        assertThat(need.repositoryCandidates()).containsExactly(new RepositoryId("order-service"));
        assertThat(need.targetHints()).containsExactly(route);
        assertThat(recordComponentNames()).doesNotContain("url", "httpMethod", "toolPayload", "rawRequest");
    }

    @Test
    void canonicalizesCandidateAndTargetOrder() {
        InformationNeed need = new InformationNeed(
                new InformationNeedId("need-cross-service"),
                InformationNeedType.CROSS_SERVICE_TARGET,
                "Find the downstream notification consumer",
                true,
                List.of(new RepositoryId("payment-service"), new RepositoryId("order-service")),
                List.of(
                        target(SemanticTargetKind.SYMBOL, "z.Symbol"),
                        target(SemanticTargetKind.ENTRY_POINT, "a.Entry")));

        assertThat(need.repositoryCandidates()).containsExactly(
                new RepositoryId("order-service"),
                new RepositoryId("payment-service"));
        assertThat(need.targetHints()).containsExactly(
                target(SemanticTargetKind.ENTRY_POINT, "a.Entry"),
                target(SemanticTargetKind.SYMBOL, "z.Symbol"));
    }

    @Test
    void validatesSourceRangeOrdering() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceRange(
                "src/main/java/OrderService.java",
                12,
                8,
                11,
                2));
    }

    private SemanticTarget target(SemanticTargetKind kind, String key) {
        return new SemanticTarget(kind, key, Optional.empty());
    }

    private List<String> recordComponentNames() {
        return Arrays.stream(InformationNeed.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
