package com.java.system.agent.codebase;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Codebase 整合僅依賴 capability SPI 與 runtime 公開 contracts 的模組邊界測試
 */
@AnalyzeClasses(
        packages = "com.java.system.agent.codebase",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CodebaseModuleArchitectureTest {

    @ArchTest
    static final ArchRule CODEBASE_DEPENDS_ONLY_ON_SPI_RUNTIME_CONTRACTS_AND_HTTP_IMPLEMENTATIONS = classes()
            .that().resideInAPackage("..codebase..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.fasterxml..", "org.springframework..", "..codebase..",
                    "..capability.spi..", "..runtime.domain..", "..runtime.port.out..");
}
