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
    static final ArchRule CAPABILITY_DEPENDS_ONLY_ON_ITS_OWN_TYPES_AND_RUNTIME_CONTRACTS = classes()
            .that().resideInAPackage("..capability..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.fasterxml.jackson..", "org.springframework..", "..capability..",
                    "..runtime.domain..", "..runtime.port.out..");
}
