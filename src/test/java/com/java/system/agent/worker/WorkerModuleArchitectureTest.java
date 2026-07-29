package com.java.system.agent.worker;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Worker 對 interaction inbound contract 的模組邊界測試
 */
@AnalyzeClasses(packages = "com.java.system.agent", importOptions = ImportOption.DoNotIncludeTests.class)
class WorkerModuleArchitectureTest {

    @ArchTest
    static final ArchRule WORKER_DEPENDS_ONLY_ON_INTERACTION_INBOUND_CONTRACTS_AND_FRAMEWORK_TYPES = classes()
            .that().resideInAPackage("..worker..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..",
                    "org.slf4j..",
                    "org.springframework..",
                    "..worker..",
                    "..interaction.port.in..");
}
