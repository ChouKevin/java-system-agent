package com.java.system.agent.capability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.planning.PlanningToolInputException;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QUERY planning input 嚴格 JSON 解碼邊界測試
 */
class StrictPlanningToolDecoderTest {

    private final StrictPlanningToolDecoder decoder = new StrictPlanningToolDecoder(new ObjectMapper(),
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void accepts_missing_optional_field_but_rejects_explicit_null() {
        Input input = decoder.decode("{\"required\":\"value\",\"limit\":2}", Input.class);

        assertThat(input.optional()).isNull();
        assertThatThrownBy(() -> decoder.decode("{\"required\":\"value\",\"optional\":null,\"limit\":2}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
    }

    @Test
    void rejects_unknown_duplicate_trailing_and_coerced_input() {
        assertThatThrownBy(() -> decoder.decode("{\"required\":\"value\",\"limit\":2,\"extra\":true}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("{\"required\":\"value\",\"limit\":2,\"limit\":3}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("{\"required\":\"value\",\"limit\":2} {}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
        assertThatThrownBy(() -> decoder.decode("{\"required\":\"value\",\"limit\":\"2\"}", Input.class))
                .isInstanceOf(PlanningToolInputException.class);
    }

    private record Input(
            @JsonProperty(required = true) @NotBlank String required,
            @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) String optional,
            @JsonProperty(required = true) @Min(1) @Max(3) int limit) {
    }
}
