package com.java.system.agent.model.prompt;

import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 以固定 placeholder set 進行一次性安全 render 的 prompt template
 */
public final class StrictPromptTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z][A-Za-z0-9]*)>");

    private final String template;
    private final Set<String> variables;
    private final StTemplateRenderer renderer;

    public StrictPromptTemplate(String template, Set<String> variables) {
        this.template = Objects.requireNonNull(template, "prompt template must not be null");
        if (this.template.isBlank()) {
            throw new IllegalArgumentException("prompt template must not be blank");
        }
        this.variables = Set.copyOf(Objects.requireNonNull(variables, "prompt template variables must not be null"));
        Set<String> placeholders = placeholders(this.template);
        if (!placeholders.equals(this.variables)) {
            throw new IllegalArgumentException("prompt template placeholder set must exactly match configured variables");
        }
        this.renderer = StTemplateRenderer.builder()
                .startDelimiterToken('<')
                .endDelimiterToken('>')
                .validationMode(ValidationMode.THROW)
                .build();
    }

    public String render(Map<String, ?> values) {
        Map<String, ?> immutableValues = Map.copyOf(Objects.requireNonNull(values,
                "prompt template render values must not be null"));
        if (!immutableValues.keySet().equals(variables)) {
            throw new IllegalArgumentException("prompt template render variable set must exactly match placeholders");
        }
        return renderer.apply(template, immutableValues);
    }

    public Set<String> variables() {
        return variables;
    }

    private static Set<String> placeholders(String template) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        Set<String> placeholders = new LinkedHashSet<>();
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return Set.copyOf(placeholders);
    }
}
