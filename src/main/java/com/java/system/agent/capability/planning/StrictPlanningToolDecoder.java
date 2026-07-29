package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.annotation.JsonSetter;
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
import java.lang.reflect.RecordComponent;

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
        Objects.requireNonNull(rawInput, "planning tool raw input must not be null");
        Objects.requireNonNull(inputType, "planning tool input type must not be null");
        if (rawInput.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new PlanningToolInputException();
        }
        try {
            rejectExplicitOptionalNulls(mapper.readTree(rawInput), inputType);
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

    private static void rejectExplicitOptionalNulls(JsonNode input, Class<?> inputType) {
        if (!input.isObject() || !inputType.isRecord()) {
            return;
        }
        for (RecordComponent component : inputType.getRecordComponents()) {
            JsonSetter setter = component.getAccessor().getAnnotation(JsonSetter.class);
            if (Objects.nonNull(setter) && input.has(component.getName()) && input.path(component.getName()).isNull()) {
                throw new PlanningToolInputException();
            }
        }
    }

    private static ObjectMapper strictMapper(ObjectMapper source) {
        return JsonMapper.builder(source.getFactory().copy())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
    }
}
