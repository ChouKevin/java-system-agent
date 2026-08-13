package com.java.system.agent.model.action;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import org.springframework.util.Assert;

/**
 * 從 planning input Java 型別產生唯一 provider schema
 */
public final class SpringAiPlanningToolSchemaFactory implements PlanningToolSchemaFactory {

    private final SchemaGenerator generator;

    public SpringAiPlanningToolSchemaFactory() {
        SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);
        builder.with(new JacksonSchemaModule(
                JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                JacksonOption.RESPECT_JSONPROPERTY_ORDER));
        builder.with(new JakartaValidationModule(
                JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                JakartaValidationOption.NOT_NULLABLE_METHOD_IS_REQUIRED));
        builder.with(
                Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT,
                Option.INLINE_ALL_SCHEMAS,
                Option.PLAIN_DEFINITION_KEYS);
        this.generator = new SchemaGenerator(builder.build());
    }

    @Override
    public String createSchema(Class<?> inputType) {
        Assert.notNull(inputType, "planning schema input type must not be null");
        if (!inputType.isRecord()) {
            throw new IllegalArgumentException("planning schema input type must be a record");
        }
        try {
            synchronized (generator) {
                return generator.generateSchema(inputType).toString();
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("planning input type cannot produce a closed schema", exception);
        }
    }
}
