package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 規劃工具唯一的嚴格 JSON 解碼邊界，先限制原始 UTF-8 再反序列化與 Bean Validation
 */
public final class StrictPlanningToolDecoder {

    public static final int MAX_UTF8_BYTES = 64 * 1024;
    private static final int MAX_DIAGNOSTIC_ITEMS = 8;
    private static final Set<String> SAFE_CONSTRAINT_NAMES = Set.of(
            "NotBlank", "NotEmpty", "NotNull", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero");
    private static final Logger LOGGER = Logger.getLogger(StrictPlanningToolDecoder.class.getName());

    private final ObjectMapper mapper;
    private final Validator validator;

    public StrictPlanningToolDecoder(Validator validator) {
        this.mapper = PlanningProtocolObjectMapper.create();
        this.validator = Objects.requireNonNull(validator, "planning validator must not be null");
    }

    public <I> I decode(String rawInput, Class<I> inputType) {
        if (Objects.isNull(rawInput)) {
            throw rejected(inputType, "ABSENT_INPUT", 0, List.of(), List.of(), 0, null);
        }
        Objects.requireNonNull(inputType, "planning tool input type must not be null");
        int rawUtf8Bytes = rawInput.getBytes(StandardCharsets.UTF_8).length;
        if (rawUtf8Bytes > MAX_UTF8_BYTES) {
            throw rejected(inputType, "INPUT_TOO_LARGE", rawUtf8Bytes, List.of(), List.of(), 0, null);
        }
        try {
            if (containsExplicitNull(mapper.readTree(rawInput))) {
                throw rejected(inputType, "EXPLICIT_NULL", rawUtf8Bytes, List.of(), List.of(), 0, null);
            }
            I input = mapper.readValue(rawInput, inputType);
            Set<ConstraintViolation<I>> violations = validator.validate(input);
            if (!violations.isEmpty()) {
                List<String> invalidFields = violations.stream()
                        .map(StrictPlanningToolDecoder::safePropertyPath)
                        .distinct()
                        .sorted()
                        .limit(MAX_DIAGNOSTIC_ITEMS)
                        .toList();
                List<String> constraints = violations.stream()
                        .map(violation -> safePropertyPath(violation) + ":" + constraintSummary(violation))
                        .distinct()
                        .sorted()
                        .limit(MAX_DIAGNOSTIC_ITEMS)
                        .toList();
                throw rejected(inputType, "BEAN_VALIDATION", rawUtf8Bytes, invalidFields, constraints,
                        violations.size(), null);
            }
            return input;
        } catch (PlanningToolInputException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected(inputType, "JSON_CONTRACT", rawUtf8Bytes, List.of(), List.of(), 0, exception);
        }
    }

    private static String constraintSummary(ConstraintViolation<?> violation) {
        Annotation annotation = violation.getConstraintDescriptor().getAnnotation();
        if (annotation instanceof Size size) {
            if (size.min() == 0) {
                return "Size(max=" + size.max() + ")";
            }
            if (size.max() == Integer.MAX_VALUE) {
                return "Size(min=" + size.min() + ")";
            }
            return "Size(min=" + size.min() + ",max=" + size.max() + ")";
        }
        if (annotation instanceof Min min) {
            return "Min(value=" + min.value() + ")";
        }
        if (annotation instanceof Max max) {
            return "Max(value=" + max.value() + ")";
        }
        String constraintName = annotation.annotationType().getSimpleName();
        return SAFE_CONSTRAINT_NAMES.contains(constraintName) ? constraintName : "Constraint";
    }

    private static String safePropertyPath(ConstraintViolation<?> violation) {
        StringJoiner path = new StringJoiner(".");
        for (Path.Node node : violation.getPropertyPath()) {
            String nodeName = node.getName();
            if (Objects.nonNull(nodeName) && !nodeName.startsWith("<")) {
                path.add(nodeName);
            }
        }
        String safePath = path.toString();
        return safePath.isBlank() ? "input" : safePath;
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
            List<String> constraints,
            int violationCount,
            Exception cause) {
        String inputTypeName = Objects.isNull(inputType) ? "UNKNOWN" : inputType.getSimpleName();
        String causeType = Objects.isNull(cause) ? "NONE" : cause.getClass().getSimpleName();
        String mappingPath = safeMappingPath(cause);
        String targetType = safeTargetType(cause);
        LOGGER.log(Level.WARNING,
                "planning tool input rejected inputType={0} reason={1} invalidFieldCount={2} "
                        + "invalidFields={3} constraintCount={4} constraints={5} violationCount={6} "
                        + "rawUtf8Bytes={7} causeType={8} mappingPath={9} targetType={10}",
                new Object[]{inputTypeName, reason, invalidFields.size(), invalidFields, constraints.size(), constraints,
                        violationCount, rawUtf8Bytes, causeType, mappingPath, targetType});
        String safeDiagnostic = "reason=" + reason;
        if (!invalidFields.isEmpty()) {
            safeDiagnostic += "; invalidFields=" + invalidFields + "; constraints=" + constraints;
        }
        String expectedJsonType = safeExpectedJsonType(cause);
        if (!"NONE".equals(expectedJsonType) && !"NONE".equals(mappingPath)) {
            safeDiagnostic += "; invalidField=" + mappingPath + "; expectedJsonType=" + expectedJsonType;
        }
        return new PlanningToolInputException(safeDiagnostic, cause);
    }

    private static String safeMappingPath(Exception cause) {
        if (!(cause instanceof JsonMappingException mappingException)) {
            return "NONE";
        }
        StringJoiner path = new StringJoiner(".");
        for (JsonMappingException.Reference reference : mappingException.getPath()) {
            String fieldName = reference.getFieldName();
            if (Objects.nonNull(fieldName) && isDeclaredRecordProperty(reference.getFrom(), fieldName)) {
                path.add(fieldName);
            }
        }
        String safePath = path.toString();
        return safePath.isBlank() ? "input" : safePath;
    }

    private static boolean isDeclaredRecordProperty(Object owner, String fieldName) {
        if (Objects.isNull(owner)) {
            return false;
        }
        Class<?> ownerType = owner instanceof Class<?> type ? type : owner.getClass();
        if (!ownerType.isRecord()) {
            return false;
        }
        for (RecordComponent component : ownerType.getRecordComponents()) {
            if (component.getName().equals(fieldName)) {
                return true;
            }
        }
        return false;
    }

    private static String safeTargetType(Exception cause) {
        if (!(cause instanceof MismatchedInputException mismatchedInput)
                || Objects.isNull(mismatchedInput.getTargetType())) {
            return "NONE";
        }
        return mismatchedInput.getTargetType().getSimpleName();
    }

    private static String safeExpectedJsonType(Exception cause) {
        if (!(cause instanceof MismatchedInputException mismatchedInput)
                || Objects.isNull(mismatchedInput.getTargetType())) {
            return "NONE";
        }
        Class<?> targetType = mismatchedInput.getTargetType();
        if (targetType.isArray() || Collection.class.isAssignableFrom(targetType)) {
            return "array";
        }
        return CharSequence.class.isAssignableFrom(targetType) || targetType == char.class
                || targetType == Character.class || targetType.isEnum() ? "string" : "NONE";
    }
}
