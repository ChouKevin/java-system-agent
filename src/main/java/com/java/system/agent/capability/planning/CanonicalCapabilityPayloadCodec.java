package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * 將已型別化 capability execution input 編碼為可重現的 opaque payload，僅 dispatcher 可解碼
 */
public final class CanonicalCapabilityPayloadCodec {

    private final ObjectMapper mapper;
    private final Validator validator;

    public CanonicalCapabilityPayloadCodec(Validator validator) {
        this.mapper = PlanningProtocolObjectMapper.create();
        this.validator = Objects.requireNonNull(validator, "capability payload validator must not be null");
    }

    public CapabilityInputPayload encode(Object input) {
        Objects.requireNonNull(input, "capability execution input must not be null");
        try {
            JsonNode tree = mapper.valueToTree(input);
            rejectNulls(tree);
            return new CapabilityInputPayload(canonicalValue(tree));
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
            String rawPayload = payload.value();
            if (rawPayload.getBytes(StandardCharsets.UTF_8).length > StrictPlanningToolDecoder.MAX_UTF8_BYTES) {
                throw contractFailure();
            }
            JsonNode tree = mapper.readTree(rawPayload);
            rejectNulls(tree);
            E input = mapper.readValue(rawPayload, inputType);
            Set<ConstraintViolation<E>> violations = validator.validate(input);
            if (!violations.isEmpty() || !rawPayload.equals(canonicalValue(mapper.valueToTree(input)))) {
                throw contractFailure();
            }
            return input;
        } catch (CapabilityExecutionContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CapabilityExecutionContractException("canonical capability payload cannot be decoded", exception);
        }
    }

    private String canonicalValue(JsonNode tree) throws Exception {
        String value = mapper.writeValueAsString(tree);
        if (value.getBytes(StandardCharsets.UTF_8).length > StrictPlanningToolDecoder.MAX_UTF8_BYTES) {
            throw new PlanningToolInputException();
        }
        return value;
    }

    private static CapabilityExecutionContractException contractFailure() {
        return new CapabilityExecutionContractException("canonical capability payload violates the planning tool contract");
    }

    private static void rejectNulls(JsonNode node) {
        if (node.isNull()) {
            throw new PlanningToolInputException();
        }
        node.forEach(CanonicalCapabilityPayloadCodec::rejectNulls);
    }
}
