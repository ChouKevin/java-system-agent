package com.java.system.agent.capability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.runtime.domain.capability.CapabilityInputPayload;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * durable canonical capability payload 的嚴格 recovery 解碼契約測試
 */
class CanonicalCapabilityPayloadCodecTest {

    private final CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void rejects_every_noncanonical_or_invalid_durable_payload_as_a_terminal_contract_defect() {
        assertRejected("{\"name\":\"value\",\"depth\":2,\"unknown\":true}");
        assertRejected("{\"name\":\"value\",\"depth\":2,\"depth\":3}");
        assertRejected("{\"name\":\"value\",\"depth\":2} {}");
        assertRejected("{\"name\":\"value\",\"depth\":\"2\"}");
        assertRejected("{\"name\":null,\"depth\":2}");
        assertRejected("{\"name\":\"value\",\"depth\":4}");
        assertRejected("null");
        assertRejected("{ \"depth\" : 2 , \"name\" : \"value\" }");
        assertRejected("{\"name\":\"value\",\"depth\":2,\"padding\":\"" + "x".repeat(64 * 1024) + "\"}");
    }

    @Test
    void preserves_the_canonical_typed_execution_input() {
        CapabilityInputPayload payload = codec.encode(new ExecutionInput("value", 2));

        assertThat(codec.decode(payload, ExecutionInput.class)).isEqualTo(new ExecutionInput("value", 2));
    }

    private void assertRejected(String rawPayload) {
        assertThatThrownBy(() -> codec.decode(new CapabilityInputPayload(rawPayload), ExecutionInput.class))
                .isInstanceOf(CapabilityExecutionContractException.class);
    }

    private record ExecutionInput(
            @JsonProperty(required = true) @NotBlank String name,
            @JsonProperty(required = true) @Max(2) int depth) {
    }
}
