package com.java.system.agent.model;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Model 整合只依賴 Spring AI 與 runtime 已公開 domain 和 outbound contract 的邊界測試
 */
@AnalyzeClasses(
        packages = "com.java.system.agent.model",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModelModuleArchitectureTest {

    @ArchTest
    static final ArchRule MODEL_DEPENDS_ONLY_ON_SPRING_AI_AND_RUNTIME_CONTRACTS = classes()
            .that().resideInAPackage("..model..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "org.springframework..", "reactor..", "tools.jackson..", "com.google.genai..",
                    "..model..",
                    "..runtime.domain..",
                    "..runtime.port.out..",
                    "..capability.tool..");
}
