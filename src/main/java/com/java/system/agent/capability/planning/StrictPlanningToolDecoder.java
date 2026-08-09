package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 規劃工具唯一的嚴格 JSON 解碼邊界，先限制原始 UTF-8 再反序列化與 Bean Validation
 */
public final class StrictPlanningToolDecoder {

    public static final int MAX_UTF8_BYTES = 64 * 1024;
    private static final Logger LOGGER = Logger.getLogger(StrictPlanningToolDecoder.class.getName());

    private final ObjectMapper mapper;
    private final Validator validator;

    public StrictPlanningToolDecoder(Validator validator) {
        this.mapper = PlanningProtocolObjectMapper.create();
        this.validator = Objects.requireNonNull(validator, "planning validator must not be null");
    }

    public <I> I decode(String rawInput, Class<I> inputType) {
        if (Objects.isNull(rawInput)) {
            throw rejected(inputType, "ABSENT_INPUT", 0, List.of(), null);
        }
        Objects.requireNonNull(inputType, "planning tool input type must not be null");
        int rawUtf8Bytes = rawInput.getBytes(StandardCharsets.UTF_8).length;
        if (rawUtf8Bytes > MAX_UTF8_BYTES) {
            throw rejected(inputType, "INPUT_TOO_LARGE", rawUtf8Bytes, List.of(), null);
        }
        try {
            if (containsExplicitNull(mapper.readTree(rawInput))) {
                throw rejected(inputType, "EXPLICIT_NULL", rawUtf8Bytes, List.of(), null);
            }
            I input = mapper.readValue(rawInput, inputType);
            Set<ConstraintViolation<I>> violations = validator.validate(input);
            if (!violations.isEmpty()) {
                List<String> invalidFields = violations.stream()
                        .map(violation -> violation.getPropertyPath().toString())
                        .sorted()
                        .toList();
                throw rejected(inputType, "BEAN_VALIDATION", rawUtf8Bytes, invalidFields, null);
            }
            return input;
        } catch (PlanningToolInputException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected(inputType, exception.getClass().getSimpleName(), rawUtf8Bytes, List.of(),
                    exception);
        }
    }

    private static boolean containsExplicitNull(JsonNode input) {
        if (Objects.isNull(input)) {
            return false;
        }
        if (input.isNull()) {
            return true;
        }
        for (JsonNode child : input) {
            if (containsExplicitNull(child)) {
                return true;
            }
        }
        return false;
    }

    private static PlanningToolInputException rejected(
            Class<?> inputType,
            String reason,
            int rawUtf8Bytes,
            List<String> invalidFields,
            Exception cause) {
        String inputTypeName = Objects.isNull(inputType) ? "UNKNOWN" : inputType.getSimpleName();
        LOGGER.log(Level.WARNING,
                "planning tool input rejected inputType={0} reason={1} invalidFieldCount={2} "
                        + "invalidFields={3} rawUtf8Bytes={4}",
                new Object[]{inputTypeName, reason, invalidFields.size(), invalidFields, rawUtf8Bytes});
        return Objects.nonNull(cause)
                ? new PlanningToolInputException(cause)
                : new PlanningToolInputException();
    }
}
