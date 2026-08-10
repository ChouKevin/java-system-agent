package com.java.system.agent.model.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StrictPromptTemplateTest {

    @Test
    void rejectsDeclaredVariablesThatDoNotExactlyMatchPlaceholders() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StrictPromptTemplate("Question: <question>", Set.of("answer")))
                .withMessageContaining("placeholder set");
    }

    @Test
    void rejectsMalformedStringTemplateExpressionsAtConstruction() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StrictPromptTemplate("Question: <question> <if(question)>",
                        Set.of("question")))
                .withMessageContaining("malformed prompt template");
    }

    @Test
    void rejectsStringTemplateExpressionsThatAreNotPlaceholdersAtConstruction() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StrictPromptTemplate("<question><first(question)>", Set.of("question")))
                .withMessageContaining("only placeholder expressions are allowed");
    }

    @Test
    void rejectsRenderVariablesThatDoNotExactlyMatchPlaceholders() {
        StrictPromptTemplate template = new StrictPromptTemplate("Question: <question>", Set.of("question"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> template.render(Map.of()))
                .withMessageContaining("render variable set");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> template.render(Map.of("question", "value", "answer", "unexpected")))
                .withMessageContaining("render variable set");
    }

    @Test
    void rendersDynamicPlaceholderSyntaxAsLiteralContent() {
        StrictPromptTemplate template = new StrictPromptTemplate("Context: <context>", Set.of("context"));

        String rendered = template.render(Map.of("context", "literal <history>"));

        assertThat(rendered).isEqualTo("Context: literal <history>");
    }
}
