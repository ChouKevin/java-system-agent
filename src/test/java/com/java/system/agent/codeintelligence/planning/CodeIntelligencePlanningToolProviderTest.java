package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.QueryPlanningToolRegistration;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;

/**
 * 驗證 repository scoped code intelligence query 必須選定一個 repository
 */
class CodeIntelligencePlanningToolProviderTest {

    @Test
    void requiresExactlyOneRepositoryForRepositoryScopedQueries() {
        CodeIntelligencePlanningToolProvider provider = new CodeIntelligencePlanningToolProvider(
                mock(JavaSemanticServiceHttpAdapter.class), new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator()));

        List<CapabilityPolicy> policies = provider.registrations().stream()
                .map(registration -> (QueryPlanningToolRegistration<?, ?>) registration)
                .map(QueryPlanningToolRegistration::policy)
                .filter(policy -> policy.name().equals("codebase_list_entry_points")
                        || policy.name().equals("codebase_lookup_api_route")
                        || policy.name().equals("codebase_suggest_api_route"))
                .toList();

        assertThat(policies)
                .extracting(CapabilityPolicy::name, CapabilityPolicy::minimumCandidates,
                        CapabilityPolicy::maximumCandidates)
                .containsExactlyInAnyOrder(
                        tuple("codebase_list_entry_points", 1, 1),
                        tuple("codebase_lookup_api_route", 1, 1),
                        tuple("codebase_suggest_api_route", 1, 1));
    }
}
