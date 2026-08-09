package com.java.system.agent.capability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.PlanningToolInputException;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import jakarta.validation.Validation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QUERY planning input 嚴格 JSON 解碼邊界測試
 */
class StrictPlanningToolDecoderTest {

    private final StrictPlanningToolDecoder decoder = new StrictPlanningToolDecoder(
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void accepts_missing_optional_field_but_rejects_explicit_null() {
        Input input = decoder.decode("{\"candidateHandles\":[\"candidate-1\"],\"required\":\"value\",\"limit\":2}", Input.class);

        assertThat(input.optional()).isNull();
        assertThatThrownBy(() -> decoder.decode("{\"candidateHandles\":[\"candidate-1\"],\"required\":\"value\",\"optional\":null,\"limit\":2}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
    }

    @Test
    void accepts_missing_nested_optional_field_but_rejects_explicit_null() {
        NestedInput input = decoder.decode("""
                {"nested":{"required":"value"}}
                """, NestedInput.class);

        assertThat(input.nested().optional()).isNull();
        assertThatThrownBy(() -> decoder.decode("""
                {"nested":{"required":"value","optional":null}}
                """, NestedInput.class))
                .isInstanceOf(PlanningToolInputException.class);
    }

    @Test
    void rejects_unknown_duplicate_trailing_and_coerced_input() {
        assertThatThrownBy(() -> decoder.decode("{\"candidateHandles\":[\"candidate-1\"],\"required\":\"value\",\"limit\":2,\"extra\":true}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("{\"candidateHandles\":[\"candidate-1\"],\"required\":\"value\",\"limit\":2,\"limit\":3}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("{\"candidateHandles\":[\"candidate-1\"],\"required\":\"value\",\"limit\":2} {}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("{\"candidateHandles\":[\"candidate-1\"],\"required\":\"value\",\"limit\":\"2\"}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
    }

    @Test
    void rejects_floating_point_values_for_integer_input() {
        assertThatThrownBy(() -> decoder.decode(
                "{\"candidateHandles\":[\"candidate-1\"],\"required\":\"value\",\"limit\":1.5}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
    }

    @Test
    void rejects_missing_null_and_blank_required_candidate_handles() {
        assertThatThrownBy(() -> decoder.decode("{\"required\":\"value\",\"limit\":2}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode(
                "{\"candidateHandles\":null,\"required\":\"value\",\"limit\":2}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode(
                "{\"candidateHandles\":[\" \"],\"required\":\"value\",\"limit\":2}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
    }

    @Test
    void logs_only_safe_input_shape_diagnostics_for_constraint_failures() {
        Logger logger = Logger.getLogger(StrictPlanningToolDecoder.class.getName());
        boolean originalUseParentHandlers = logger.getUseParentHandlers();
        List<LogRecord> records = new ArrayList<>();
        Handler handler = recordingHandler(records);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);

        try {
            assertThatThrownBy(() -> decoder.decode(
                    "{\"candidateHandles\":[\"SENSITIVE_VALUE\"],\"required\":\" \",\"limit\":2}", Input.class))
                    .isInstanceOf(PlanningToolInputException.class);

            assertThat(records).hasSize(1);
            assertThat(records.getFirst().getParameters()).contains("Input", "BEAN_VALIDATION", 1)
                    .doesNotContain("SENSITIVE_VALUE");
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(originalUseParentHandlers);
        }
    }

    @Test
    void planning_protocol_components_do_not_accept_a_host_object_mapper() {
        assertThat(Arrays.stream(StrictPlanningToolDecoder.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                .doesNotContain(ObjectMapper.class);
        assertThat(Arrays.stream(CanonicalCapabilityPayloadCodec.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                .doesNotContain(ObjectMapper.class);
    }

    private record Input(
            @JsonProperty(required = true) @NotNull List<@NotBlank String> candidateHandles,
            @JsonProperty(required = true) @NotBlank String required,
            @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) String optional,
            @JsonProperty(required = true) @Min(1) @Max(3) int limit) {
    }

    private record NestedInput(@JsonProperty(required = true) @NotNull @Valid Nested nested) {
    }

    private record Nested(
            @JsonProperty(required = true) @NotBlank String required,
            @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) String optional) {
    }

    private static Handler recordingHandler(List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }
}
