package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code intelligence planning execution input 的 canonical payload 可選欄位邊界測試
 */
class CodeIntelligencePlanningPayloadTest {

    @Test
    void decodesMissingOptionalEntryPointTypeFromCanonicalPayload() {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        CapabilityInputPayload payload = codec.encode(new ListEntryPointsExecutionInput(null));

        ListEntryPointsExecutionInput decoded = codec.decode(payload, ListEntryPointsExecutionInput.class);

        assertThat(payload.value()).isEqualTo("{}");
        assertThat(decoded.type()).isNull();
    }
}
