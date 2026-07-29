package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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

    public StrictPlanningToolDecoder(ObjectMapper mapper, Validator validator) {
        this.mapper = strictMapper(Objects.requireNonNull(mapper, "planning mapper must not be null"));
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

    private static ObjectMapper strictMapper(ObjectMapper source) {
        return JsonMapper.builder(source.getFactory().copy())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
    }
}
