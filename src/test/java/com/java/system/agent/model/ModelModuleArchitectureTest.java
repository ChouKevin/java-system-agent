package com.java.system.agent.model;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Model 整合只依賴 schema/model adapter 與 answering 已公開 domain 和 outbound contract 的邊界測試
 */
@AnalyzeClasses(
        packages = {"com.java.system.agent.model", "com.java.system.agent.capability.planning"},
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModelModuleArchitectureTest {

    @ArchTest
    static final ArchRule MODEL_DEPENDS_ONLY_ON_APPROVED_ADAPTER_AND_RUNTIME_CONTRACTS = classes()
            .that().resideInAPackage("..model..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.fasterxml.jackson..", "com.github.victools..", "jakarta..",
                    "org.springframework..", "reactor..", "tools.jackson..", "com.google.genai..",
                    "..model..",
                    "..answering.domain..",
                    "..answering.port.out..",
                    "..capability.planning..");

    @ArchTest
    static final ArchRule PROMPT_CATALOG_DOES_NOT_DEPEND_ON_THE_ANSWERING_APPLICATION_LOOP = noClasses()
            .that().resideInAPackage("..model.prompt..")
            .and().doNotHaveSimpleName("package-info")
            .should().dependOnClassesThat()
            .resideInAPackage("..answering.application.loop..");

    @ArchTest
    static final ArchRule CAPABILITY_PLANNING_DOES_NOT_DEPEND_ON_PROMPT_OR_RENDERING_LIBRARIES = noClasses()
            .that().resideInAPackage("..capability.planning..")
            .and().doNotHaveSimpleName("package-info")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "..model.prompt..",
                    "org.springframework.core.io..",
                    "org.springframework.ai..",
                    "org.stringtemplate..");
}
