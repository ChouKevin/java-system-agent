package com.java.system.agent.capability;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Capability 整合僅依賴 answering 已公開 domain 與 outbound contract 的模組邊界測試
 */
@AnalyzeClasses(
        packages = "com.java.system.agent.capability",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CapabilityModuleArchitectureTest {

    @ArchTest
    static final ArchRule CAPABILITY_IMPLEMENTATION_DEPENDS_ONLY_ON_ITS_OWN_TYPES_AND_RUNTIME_CONTRACTS = classes()
            .that().resideInAPackage("..capability..")
            .and().doNotHaveSimpleName("package-info")
            .and().resideOutsideOfPackage("..capability.planning..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..capability..",
                    "..answering.domain..", "..answering.port.out..");

    @ArchTest
    static final ArchRule CAPABILITY_PLANNING_DEPENDS_ON_JACKSON_AND_RUNTIME_CONTRACTS = classes()
            .that().resideInAPackage("..capability.planning..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.fasterxml.jackson..", "jakarta..", "org.springframework..", "..capability..",
                    "..answering.domain..", "..answering.port.out..");

    @ArchTest
    static final ArchRule CANDIDATE_BOUND_PLANNING_STAYS_FRAMEWORK_AND_ADAPTER_NEUTRAL = classes()
            .that().haveSimpleNameStartingWith("CandidateBound")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..capability.planning..", "..capability.spi..",
                    "..answering.domain..", "..answering.port.out..");

    @ArchTest
    static final ArchRule CAPABILITY_HAS_NO_SPRING_AI_DEPENDENCIES = noClasses()
            .that().resideInAPackage("..capability..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.ai..");

    @ArchTest
    static final ArchRule CAPABILITY_EXECUTOR_SPI_DEPENDS_ONLY_ON_JDK_AND_RUNTIME_CONTRACTS = classes()
            .that().resideInAPackage("..capability.spi..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..capability.spi..", "..answering.domain..", "..answering.port.out..");
}
