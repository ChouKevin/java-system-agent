package com.java.system.agent.runtime;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.java.system.agent.runtime",
        importOptions = ImportOption.DoNotIncludeTests.class)
class RuntimeKernelArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_DEPENDS_ONLY_ON_JDK_AND_DOMAIN = classes()
            .that().resideInAPackage("..runtime.domain..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..runtime.domain..");

    @ArchTest
    static final ArchRule APPLICATION_DEPENDS_ONLY_ON_KERNEL = classes()
            .that().resideInAPackage("..runtime.application..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "..runtime.application..",
                    "..runtime.domain..",
                    "..runtime.port.in..",
                    "..runtime.port.out..");

    @ArchTest
    static final ArchRule INBOUND_PORTS_ONLY_DEPEND_ON_DOMAIN_AND_JAVA = noClasses()
            .that().resideInAPackage("..runtime.port.in..")
            .and().doNotHaveSimpleName("package-info")
            .should().dependOnClassesThat()
            .resideOutsideOfPackages("java..", "..runtime.domain..", "..runtime.port.in..");

    @ArchTest
    static final ArchRule OUTBOUND_PORT_DEPENDS_ONLY_ON_JDK_AND_DOMAIN = classes()
            .that().resideInAPackage("..runtime.port.out..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..runtime.domain..", "..runtime.port.out..");
}
