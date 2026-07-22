package com.java.system.agent.analysis.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResolutionStrategyTest {

    @Test
    void should_classify_every_strategy_when_checking_guessed_status() {
        Map<ResolutionStrategy, Boolean> expected = Map.ofEntries(
                Map.entry(ResolutionStrategy.JAVA_SYMBOL_SOLVER, false),
                Map.entry(ResolutionStrategy.SAME_CLASS_METHOD, false),
                Map.entry(ResolutionStrategy.STATIC_METHOD, false),
                Map.entry(ResolutionStrategy.SPRING_BEAN_BY_TYPE, false),
                Map.entry(ResolutionStrategy.INTERFACE_SINGLE_IMPL, false),
                Map.entry(ResolutionStrategy.INTERFACE_MULTI_IMPL, true),
                Map.entry(ResolutionStrategy.HEURISTIC_NAME_MATCH, true),
                Map.entry(ResolutionStrategy.UNRESOLVED, true),
                Map.entry(ResolutionStrategy.JDT_CALL_HIERARCHY, false),
                Map.entry(ResolutionStrategy.JDT_DEFINITION_FALLBACK, false),
                Map.entry(ResolutionStrategy.SPRING_BEAN_BY_QUALIFIER, false),
                Map.entry(ResolutionStrategy.SPRING_BEAN_BY_PRIMARY, false),
                Map.entry(ResolutionStrategy.SPRING_SINGLE_IMPLEMENTATION, false),
                Map.entry(ResolutionStrategy.MYBATIS_MAPPER, false),
                Map.entry(ResolutionStrategy.LOMBOK_GENERATED, false),
                Map.entry(ResolutionStrategy.EXTERNAL_LIBRARY, true),
                Map.entry(ResolutionStrategy.FEIGN_CLIENT, true),
                Map.entry(ResolutionStrategy.BUSINESS_READ_FORBIDDEN, true),
                Map.entry(ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES, true),
                Map.entry(ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE, true),
                Map.entry(ResolutionStrategy.UNRESOLVED_TARGET, true));

        assertThat(expected).hasSize(ResolutionStrategy.values().length);
        assertThat(ResolutionStrategy.values())
                .allSatisfy(strategy -> assertThat(strategy.isGuessed()).isEqualTo(expected.get(strategy)));
    }
}
