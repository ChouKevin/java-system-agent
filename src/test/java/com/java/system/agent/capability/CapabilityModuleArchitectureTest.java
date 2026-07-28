package com.java.system.agent.capability;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Capability 整合僅依賴 runtime 已公開 domain 與 outbound contract 的模組邊界測試
 */
@AnalyzeClasses(
        packages = "com.java.system.agent.capability",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CapabilityModuleArchitectureTest {

    @ArchTest
    static final ArchRule CAPABILITY_IMPLEMENTATION_DEPENDS_ONLY_ON_ITS_OWN_TYPES_AND_RUNTIME_CONTRACTS = classes()
            .that().resideInAPackage("..capability..")
            .and().doNotHaveSimpleName("package-info")
            .and().resideOutsideOfPackage("..capability.tool..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..capability..",
                    "..runtime.domain..", "..runtime.port.out..");

    @ArchTest
    static final ArchRule CAPABILITY_TOOL_DEPENDS_ON_SPRING_AI_JACKSON_AND_RUNTIME_CONTRACTS = classes()
            .that().resideInAPackage("..capability.tool..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.fasterxml.jackson..", "org.springframework..", "..capability..",
                    "..runtime.domain..", "..runtime.port.out..");

    @ArchTest
    static final ArchRule CAPABILITY_EXECUTOR_SPI_DEPENDS_ONLY_ON_JDK_AND_RUNTIME_CONTRACTS = classes()
            .that().resideInAPackage("..capability.spi..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..capability.spi..", "..runtime.domain..", "..runtime.port.out..");
}
