package com.java.system.agent.analysis;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@AnalyzeClasses(
        packages = "com.java.system.agent.analysis",
        importOptions = ImportOption.DoNotIncludeTests.class)
class AnalysisKernelArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_DEPENDS_ONLY_ON_JDK_AND_DOMAIN = classes()
            .that().resideInAPackage("..analysis.domain..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..analysis.domain..");

    @ArchTest
    static final ArchRule APPLICATION_DEPENDS_ONLY_ON_KERNEL = classes()
            .that().resideInAPackage("..analysis.application..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "..analysis.application..",
                    "..analysis.domain..",
                    "..analysis.port.out..");

    @ArchTest
    static final ArchRule OUTBOUND_PORT_DEPENDS_ONLY_ON_JDK_AND_DOMAIN = classes()
            .that().resideInAPackage("..analysis.port.out..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..analysis.domain..", "..analysis.port.out..");
}
