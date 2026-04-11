package com.java.system.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

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
