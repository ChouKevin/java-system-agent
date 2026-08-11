package com.java.system.agent.answering.domain.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QuestionPlan 不變量測試
 */
class QuestionPlanTest {

    @Test
    void preserves_the_declared_need_order() {
        InformationNeed first = new InformationNeed(new InformationNeedId("N1"), "Find the entry point");
        InformationNeed second = new InformationNeed(new InformationNeedId("N2"), "Find the persistence boundary");

        List<InformationNeed> needs = new ArrayList<>(List.of(first, second));

        QuestionPlan plan = new QuestionPlan(needs);
        needs.clear();

        assertThat(plan.needs()).containsExactly(first, second);
    }

    @Test
    void rejects_duplicate_need_identifiers() {
        InformationNeedId identifier = new InformationNeedId("N1");

        assertThatThrownBy(() -> new QuestionPlan(List.of(
                new InformationNeed(identifier, "Find the entry point"),
                new InformationNeed(identifier, "Find the persistence boundary"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_more_than_twelve_needs() {
        List<InformationNeed> needs = List.of(
                need("N1"), need("N2"), need("N3"), need("N4"), need("N5"), need("N6"),
                need("N7"), need("N8"), need("N9"), need("N10"), need("N11"), need("N12"), need("N13"));

        assertThatThrownBy(() -> new QuestionPlan(needs))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static InformationNeed need(String id) {
        return new InformationNeed(new InformationNeedId(id), "Need " + id);
    }
}
