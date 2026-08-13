package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.PlanningToolInputException;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

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

    @Test
    void rejects_omitted_or_null_required_suggest_limit_before_any_executor_can_run() {
        StrictPlanningToolDecoder decoder = new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator());

        assertThatThrownBy(() -> decoder.decode("""
                {"questionToResolve":"Suggest","rationale":"Need route","apiPath":"/orders"}
                """, SuggestApiRoutePlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("""
                {"questionToResolve":"Suggest","rationale":"Need route","apiPath":"/orders","limit":null}
                """, SuggestApiRoutePlanningInput.class)).isInstanceOf(PlanningToolInputException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSourceRanges")
    void rejects_invalid_source_ranges_at_the_shared_typed_boundary(String scenario, String input) {
        StrictPlanningToolDecoder decoder = new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator());

        assertThatThrownBy(() -> decoder.decode(input, GetSourceSegmentPlanningInput.class))
                .isInstanceOf(PlanningToolInputException.class);
    }

    @Test
    void decodes_empty_and_multiline_half_open_source_ranges() {
        StrictPlanningToolDecoder decoder = new StrictPlanningToolDecoder(Validation.buildDefaultValidatorFactory().getValidator());
        GetSourceSegmentPlanningInput empty = decoder.decode(sourceSegment("{\"line\":0,\"character\":0}",
                "{\"line\":0,\"character\":0}"), GetSourceSegmentPlanningInput.class);
        GetSourceSegmentPlanningInput multiline = decoder.decode(sourceSegment("{\"line\":0,\"character\":5}",
                "{\"line\":1,\"character\":0}"), GetSourceSegmentPlanningInput.class);

        assertThat(empty.location().range().start()).isEqualTo(empty.location().range().end());
        assertThat(multiline.location().range().end().line()).isEqualTo(1);
    }

    private static Stream<Arguments> invalidSourceRanges() {
        return Stream.of(
                Arguments.of("negative coordinate", sourceSegment("{\"line\":-1,\"character\":0}",
                        "{\"line\":0,\"character\":0}")),
                Arguments.of("missing coordinate", sourceSegment("{\"line\":0}",
                        "{\"line\":0,\"character\":0}")),
                Arguments.of("reversed range", sourceSegment("{\"line\":1,\"character\":0}",
                        "{\"line\":0,\"character\":5}")));
    }

    private static String sourceSegment(String start, String end) {
        return """
                {"questionToResolve":"Read","rationale":"Need source","location":{"sourceFile":"src/Orders.java",
                "range":{"start":%s,"end":%s}}}
                """.formatted(start, end);
    }
}
