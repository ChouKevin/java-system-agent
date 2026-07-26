package com.java.system.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(Application.class);

    @Test
    @DisplayName("modulith constraints should pass")
    void verifyModularStructure() {
        modules.verify();
    }

    @Test
    @DisplayName("the agent v2 runtime module should exist")
    void runtimeModuleShouldExist() {
        assertTrue(modules.getModuleByName("runtime").isPresent(),
                "Expected modulith module to exist: runtime");
    }

    @Test
    @DisplayName("agent v2 runtime kernel should depend on no other module")
    void runtimeKernelShouldHaveNoModuleDependencies() {
        ApplicationModule runtime = requireRuntime();

        assertTrue(runtime.getDirectDependencies(modules).isEmpty(),
                () -> "Expected runtime kernel to have no module dependencies, but found: "
                        + runtime.getDirectDependencies(modules).uniqueModules().toList());
    }

    @Test
    @DisplayName("runtime kernel should expose only its contract packages")
    void runtimeKernelShouldExposeOnlyContractPackages() {
        Set<String> exposed = requireRuntime().getNamedInterfaces().stream()
                .filter(namedInterface -> !namedInterface.isUnnamed())
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("domain", "port-in", "port-out"), exposed,
                "Runtime kernel must expose exactly its domain and port packages");
    }

    private ApplicationModule requireRuntime() {
        return modules.getModuleByName("runtime")
                .orElseThrow(() -> new IllegalStateException("Missing module: runtime"));
    }
}
