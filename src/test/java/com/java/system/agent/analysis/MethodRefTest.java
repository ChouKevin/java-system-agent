package com.java.system.agent.analysis;

import com.java.system.agent.analysis.model.MethodRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MethodRefTest {

    @Test
    void should_create_successfully_when_all_fields_are_valid() {
        MethodRef ref = new MethodRef("com.example", "MyClass", "myMethod(String)");

        assertThat(ref.packageName()).isEqualTo("com.example");
        assertThat(ref.className()).isEqualTo("MyClass");
        assertThat(ref.methodSignature()).isEqualTo("myMethod(String)");
    }

    @Test
    void should_throw_when_packageName_is_blank() {
        assertThatThrownBy(() -> new MethodRef("  ", "MyClass", "myMethod()"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("packageName");
    }

    @Test
    void should_throw_when_className_is_blank() {
        assertThatThrownBy(() -> new MethodRef("com.example", "", "myMethod()"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("className");
    }

    @Test
    void should_throw_when_methodSignature_is_blank() {
        assertThatThrownBy(() -> new MethodRef("com.example", "MyClass", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("methodSignature");
    }
}
