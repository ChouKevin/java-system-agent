package com.java.system.agent.codebase.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.runtime.domain.capability.CapabilityInputPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * codebase planning execution input 的 canonical payload 可選欄位邊界測試
 */
class CodebasePlanningPayloadTest {

    @Test
    void decodesMissingOptionalEntryPointTypeFromCanonicalPayload() {
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(new ObjectMapper());
        CapabilityInputPayload payload = codec.encode(new ListEntryPointsExecutionInput(null));

        ListEntryPointsExecutionInput decoded = codec.decode(payload, ListEntryPointsExecutionInput.class);

        assertThat(payload.value()).isEqualTo("{}");
        assertThat(decoded.type()).isNull();
    }
}
