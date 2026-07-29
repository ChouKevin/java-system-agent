package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * 規劃工具唯一的嚴格 JSON 解碼邊界，先限制原始 UTF-8 再反序列化與 Bean Validation
 */
public final class StrictPlanningToolDecoder {

    public static final int MAX_UTF8_BYTES = 64 * 1024;

    private final ObjectMapper mapper;
    private final Validator validator;

    public StrictPlanningToolDecoder(Validator validator) {
        this.mapper = PlanningProtocolObjectMapper.create();
        this.validator = Objects.requireNonNull(validator, "planning validator must not be null");
    }

    public <I> I decode(String rawInput, Class<I> inputType) {
        if (Objects.isNull(rawInput)) {
            throw new PlanningToolInputException();
        }
        Objects.requireNonNull(inputType, "planning tool input type must not be null");
        if (rawInput.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new PlanningToolInputException();
        }
        try {
            rejectExplicitNulls(mapper.readTree(rawInput));
            I input = mapper.readValue(rawInput, inputType);
            Set<ConstraintViolation<I>> violations = validator.validate(input);
            if (!violations.isEmpty()) {
                throw new PlanningToolInputException();
            }
            return input;
        } catch (PlanningToolInputException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PlanningToolInputException(exception);
        }
    }

    private static void rejectExplicitNulls(JsonNode input) {
        if (input.isNull()) {
            throw new PlanningToolInputException();
        }
        input.forEach(StrictPlanningToolDecoder::rejectExplicitNulls);
    }
}
