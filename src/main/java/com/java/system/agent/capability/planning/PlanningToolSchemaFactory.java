package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Objects;

/**
 * 從 planning input Java 型別產生唯一 provider schema，並驗證已註冊 schema 忠實表達宣告約束
 */
public final class PlanningToolSchemaFactory {

    private static final String NONBLANK_PATTERN = ".*\\S.*";

    private final ObjectMapper mapper;

    public PlanningToolSchemaFactory(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "planning schema mapper must not be null");
    }

    public String schemaFor(Class<?> inputType) {
        try {
            return mapper.writeValueAsString(schemaNodeFor(inputType));
        } catch (Exception exception) {
            throw new IllegalArgumentException("planning input type cannot produce a closed schema", exception);
        }
    }

    public void verifyRegisteredSchema(Class<?> inputType, String schema) {
        Objects.requireNonNull(inputType, "planning schema input type must not be null");
        Objects.requireNonNull(schema, "registered planning schema must not be null");
        try {
            JsonNode registered = mapper.readTree(schema);
            verifyContract(inputType, registered);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("registered planning schema is invalid", exception);
        }
    }

    private JsonNode schemaNodeFor(Class<?> inputType) throws Exception {
        Objects.requireNonNull(inputType, "planning schema input type must not be null");
        JsonNode root = mapper.readTree(JsonSchemaGenerator.generateForType(inputType));
        enrichRecordSchema(inputType, object(root, "planning schema root"));
        closeObjects(root);
        verifyContract(inputType, root);
        return root;
    }

    private static void enrichRecordSchema(Class<?> inputType, ObjectNode root) {
        if (!inputType.isRecord()) {
            throw new IllegalArgumentException("planning schema input type must be a record");
        }
        ObjectNode properties = object(root.path("properties"), "planning schema properties");
        ArrayNode required = required(root);
        for (RecordComponent component : inputType.getRecordComponents()) {
            ObjectNode property = object(properties.path(component.getName()), "planning schema property " + component.getName());
            JsonProperty jsonProperty = component.getAccessor().getAnnotation(JsonProperty.class);
            if (Objects.nonNull(jsonProperty) && jsonProperty.required()) {
                addRequired(required, component.getName());
            }
            enrichType(component.getAccessor().getAnnotatedReturnType(), component.getAccessor().getAnnotations(), property);
        }
    }

    private static void enrichType(AnnotatedType type, Annotation[] annotations, ObjectNode schema) {
        Class<?> rawType = rawType(type.getType());
        Min min = annotation(annotations, Min.class);
        Max max = annotation(annotations, Max.class);
        if (Objects.nonNull(min)) {
            schema.put("minimum", min.value());
        }
        if (Objects.nonNull(max)) {
            schema.put("maximum", max.value());
        }
        if (Objects.nonNull(annotation(annotations, NotBlank.class))) {
            schema.put("minLength", 1);
            schema.put("pattern", NONBLANK_PATTERN);
        }
        if (Objects.nonNull(annotation(annotations, NotEmpty.class))) {
            applyNotEmpty(rawType, schema);
        }
        if (rawType.isEnum()) {
            ArrayNode values = schema.putArray("enum");
            for (Object value : rawType.getEnumConstants()) {
                values.add(((Enum<?>) value).name());
            }
        }
        if (Collection.class.isAssignableFrom(rawType) && type instanceof AnnotatedParameterizedType parameterized) {
            AnnotatedType[] arguments = parameterized.getAnnotatedActualTypeArguments();
            if (arguments.length != 1) {
                throw new IllegalArgumentException("planning collection input must have exactly one element type");
            }
            AnnotatedType elementType = arguments[0];
            enrichType(elementType, elementType.getAnnotations(), object(schema.path("items"), "planning schema collection item"));
        }
    }

    private static void verifyContract(Class<?> inputType, JsonNode root) {
        ObjectNode rootObject = object(root, "planning schema root");
        verifyClosedObjects(rootObject);
        if (!inputType.isRecord()) {
            throw new IllegalArgumentException("planning schema input type must be a record");
        }
        ObjectNode properties = object(rootObject.path("properties"), "planning schema properties");
        JsonNode required = rootObject.path("required");
        for (RecordComponent component : inputType.getRecordComponents()) {
            ObjectNode property = object(properties.path(component.getName()), "planning schema property " + component.getName());
            JsonProperty jsonProperty = component.getAccessor().getAnnotation(JsonProperty.class);
            boolean expectedRequired = Objects.nonNull(jsonProperty) && jsonProperty.required();
            if (required(required, component.getName()) != expectedRequired) {
                throw new IllegalArgumentException("planning schema required contract differs for " + component.getName());
            }
            verifyType(component.getAccessor().getAnnotatedReturnType(), component.getAccessor().getAnnotations(), property);
        }
    }

    private static void verifyType(AnnotatedType type, Annotation[] annotations, ObjectNode schema) {
        Class<?> rawType = rawType(type.getType());
        if (allowsNull(schema)) {
            throw new IllegalArgumentException("planning schema must reject explicit null");
        }
        Min min = annotation(annotations, Min.class);
        Max max = annotation(annotations, Max.class);
        if (Objects.nonNull(min) && schema.path("minimum").asLong(Long.MIN_VALUE) != min.value()) {
            throw new IllegalArgumentException("planning schema minimum differs from input constraint");
        }
        if (Objects.nonNull(max) && schema.path("maximum").asLong(Long.MAX_VALUE) != max.value()) {
            throw new IllegalArgumentException("planning schema maximum differs from input constraint");
        }
        if (Objects.nonNull(annotation(annotations, NotBlank.class))
                && (schema.path("minLength").asInt() < 1 || !NONBLANK_PATTERN.equals(schema.path("pattern").asText()))) {
            throw new IllegalArgumentException("planning schema string constraint differs from input constraint");
        }
        if (Objects.nonNull(annotation(annotations, NotEmpty.class))) {
            verifyNotEmpty(rawType, schema);
        }
        if (rawType.isEnum()) {
            for (Object value : rawType.getEnumConstants()) {
                if (!containsText(schema.path("enum"), ((Enum<?>) value).name())) {
                    throw new IllegalArgumentException("planning schema enum differs from input type");
                }
            }
        }
        if (Collection.class.isAssignableFrom(rawType) && type instanceof AnnotatedParameterizedType parameterized) {
            AnnotatedType[] arguments = parameterized.getAnnotatedActualTypeArguments();
            if (arguments.length != 1) {
                throw new IllegalArgumentException("planning collection input must have exactly one element type");
            }
            verifyType(arguments[0], arguments[0].getAnnotations(), object(schema.path("items"), "planning schema collection item"));
        }
    }

    private static void applyNotEmpty(Class<?> rawType, ObjectNode schema) {
        if (Collection.class.isAssignableFrom(rawType)) {
            schema.put("minItems", 1);
        } else if (CharSequence.class.isAssignableFrom(rawType)) {
            schema.put("minLength", 1);
        } else {
            throw new IllegalArgumentException("planning @NotEmpty constraint requires a collection or string");
        }
    }

    private static void verifyNotEmpty(Class<?> rawType, ObjectNode schema) {
        int length = Collection.class.isAssignableFrom(rawType)
                ? schema.path("minItems").asInt()
                : schema.path("minLength").asInt();
        if (length < 1) {
            throw new IllegalArgumentException("planning schema collection constraint differs from input constraint");
        }
    }

    private static void closeObjects(JsonNode node) {
        if (node.isObject()) {
            ((ObjectNode) node).put("additionalProperties", false);
        }
        node.forEach(PlanningToolSchemaFactory::closeObjects);
    }

    private static void verifyClosedObjects(JsonNode node) {
        if (node.isObject() && node.path("type").asText().equals("object") && node.path("additionalProperties").asBoolean(true)) {
            throw new IllegalArgumentException("planning schema object must be closed");
        }
        node.forEach(PlanningToolSchemaFactory::verifyClosedObjects);
    }

    private static ArrayNode required(ObjectNode root) {
        JsonNode required = root.path("required");
        if (required.isArray()) {
            return (ArrayNode) required;
        }
        return root.putArray("required");
    }

    private static void addRequired(ArrayNode required, String name) {
        if (!containsText(required, name)) {
            required.add(name);
        }
    }

    private static boolean required(JsonNode values, String name) {
        return containsText(values, name);
    }

    private static boolean containsText(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowsNull(JsonNode schema) {
        if (schema.path("type").isArray() && containsText(schema.path("type"), "null")) {
            return true;
        }
        return schema.path("type").asText().equals("null") || containsNull(schema.path("enum"));
    }

    private static boolean containsNull(JsonNode values) {
        for (JsonNode value : values) {
            if (value.isNull()) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> rawType) {
            return rawType;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawType) {
            return rawType;
        }
        throw new IllegalArgumentException("planning input type must have a concrete raw type");
    }

    private static <A extends Annotation> A annotation(Annotation[] annotations, Class<A> type) {
        for (Annotation annotation : annotations) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        return null;
    }

    private static ObjectNode object(JsonNode node, String description) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(description + " must be an object");
        }
        return (ObjectNode) node;
    }
}
