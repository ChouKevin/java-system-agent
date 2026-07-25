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
    @DisplayName("expected application modules should exist")
    void expectedModulesShouldExist() {
        assertModuleExists("api");
        assertModuleExists("analysis");
        assertModuleExists("runtime");
        assertModuleExists("ai");
        assertModuleExists("git");
        assertModuleExists("slack");
        assertModuleExists("ratelimit");
        assertModuleExists("common");
    }

    @Test
    @DisplayName("key module dependency directions should stay stable")
    void keyDependencyDirectionsShouldStayStable() {
        assertDirectDependency("api", "analysis");
        assertDirectDependency("api", "git");
        assertDirectDependency("ai", "analysis");
        assertDirectDependency("git", "analysis");
        assertDirectDependency("slack", "ai");
        assertDirectDependency("slack", "ratelimit");
    }

    @Test
    @DisplayName("agent v2 runtime kernel should depend on no other module")
    void runtimeKernelShouldHaveNoModuleDependencies() {
        ApplicationModule runtime = modules.getModuleByName("runtime")
                .orElseThrow(() -> new IllegalStateException("Missing module: runtime"));

        assertTrue(runtime.getDirectDependencies(modules).isEmpty(),
                () -> "Expected runtime kernel to have no module dependencies, but found: "
                        + runtime.getDirectDependencies(modules).uniqueModules().toList());
    }

    @Test
    @DisplayName("runtime kernel should expose only its contract packages")
    void runtimeKernelShouldExposeOnlyContractPackages() {
        ApplicationModule runtime = modules.getModuleByName("runtime")
                .orElseThrow(() -> new IllegalStateException("Missing module: runtime"));

        Set<String> exposed = runtime.getNamedInterfaces().stream()
                .filter(namedInterface -> !namedInterface.isUnnamed())
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("domain", "port-in", "port-out"), exposed,
                "Runtime kernel must expose exactly its domain and port packages");
    }

    private void assertModuleExists(String name) {
        assertTrue(modules.getModuleByName(name).isPresent(),
                () -> "Expected modulith module to exist: " + name);
    }

    private void assertDirectDependency(String moduleName, String dependencyName) {
        ApplicationModule module = modules.getModuleByName(moduleName)
                .orElseThrow(() -> new IllegalStateException("Missing module: " + moduleName));

        boolean present = module.getDirectDependencies(modules).containsModuleNamed(dependencyName);
        assertTrue(present, () -> "Expected direct dependency " + moduleName + " -> " + dependencyName);
    }
}
