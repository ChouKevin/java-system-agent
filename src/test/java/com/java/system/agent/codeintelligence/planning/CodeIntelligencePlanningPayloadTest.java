package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.PlanningToolInputException;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeIntelligencePlanningPayloadTest {

    @Test
    void decodes_exact_candidate_free_method_target_and_rejects_runtime_scope() {
        StrictPlanningToolDecoder decoder = new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator());
        GetMethodSourcePlanningInput input = decoder.decode("""
                {"questionToResolve":"Read","rationale":"Need source","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},"methodName":"find","parameterTypes":[]}}
                """, GetMethodSourcePlanningInput.class);

        assertThat(input.target().methodName()).isEqualTo("find");
        assertThatThrownBy(() -> decoder.decode("""
                {"questionToResolve":"Read","rationale":"Need source","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},"methodName":"find","parameterTypes":[]},"repoId":"orders"}
                """, GetMethodSourcePlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
    }
}
