package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.system.agent.runtime.domain.capability.CapabilityInputPayload;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 將已型別化 capability execution input 編碼為可重現的 opaque payload，僅 dispatcher 可解碼
 */
public final class CanonicalCapabilityPayloadCodec {

    private final ObjectMapper mapper;

    public CanonicalCapabilityPayloadCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "capability payload mapper must not be null").copy()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public CapabilityInputPayload encode(Object input) {
        Objects.requireNonNull(input, "capability execution input must not be null");
        try {
            JsonNode tree = mapper.valueToTree(input);
            rejectNulls(tree);
            String value = mapper.writeValueAsString(tree);
            if (value.getBytes(StandardCharsets.UTF_8).length > StrictPlanningToolDecoder.MAX_UTF8_BYTES) {
                throw new PlanningToolInputException();
            }
            return new CapabilityInputPayload(value);
        } catch (PlanningToolInputException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PlanningToolInputException(exception);
        }
    }

    public <E> E decode(CapabilityInputPayload payload, Class<E> inputType) {
        Objects.requireNonNull(payload, "capability payload must not be null");
        Objects.requireNonNull(inputType, "capability execution input type must not be null");
        try {
            return mapper.readValue(payload.value(), inputType);
        } catch (Exception exception) {
            throw new IllegalStateException("canonical capability payload cannot be decoded", exception);
        }
    }

    private static void rejectNulls(JsonNode node) {
        if (node.isNull()) {
            throw new PlanningToolInputException();
        }
        node.forEach(CanonicalCapabilityPayloadCodec::rejectNulls);
    }
}
