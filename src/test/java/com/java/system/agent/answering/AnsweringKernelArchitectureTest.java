package com.java.system.agent.answering;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.java.system.agent.answering",
        importOptions = ImportOption.DoNotIncludeTests.class)
class AnsweringKernelArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_DEPENDS_ONLY_ON_JDK_DOMAIN_AND_JACKSON_ANNOTATIONS = classes()
            .that().resideInAPackage("..answering.domain..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..answering.domain..", "com.fasterxml.jackson.annotation..");

    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_APPLICATION_OR_PORTS = noClasses()
            .that().resideInAPackage("..answering.domain..")
            .and().doNotHaveSimpleName("package-info")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..answering.application..", "..answering.port..");

    @ArchTest
    static final ArchRule APPLICATION_DEPENDS_ONLY_ON_KERNEL = classes()
            .that().resideInAPackage("..answering.application..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "..answering.application..",
                    "..answering.domain..",
                    "..answering.port.in..",
                    "..answering.port.out..");

    @ArchTest
    static final ArchRule VALIDATED_AGENT_LOOP_RESIDES_IN_DEDICATED_PACKAGE = classes()
            .that().haveSimpleName("ValidatedAgentLoop")
            .should().resideInAPackage("..answering.application.loop");

    @ArchTest
    static final ArchRule APPLICATION_LOOP_DEPENDS_ONLY_ON_KERNEL_CONTRACTS = classes()
            .that().resideInAPackage("..answering.application.loop..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "..answering.application.loop..",
                    "..answering.application.state..",
                    "..answering.application.validation..",
                    "..answering.domain..",
                    "..answering.port.in..",
                    "..answering.port.out..");

    @ArchTest
    static final ArchRule INBOUND_PORTS_ONLY_DEPEND_ON_DOMAIN_AND_JAVA = noClasses()
            .that().resideInAPackage("..answering.port.in..")
            .and().doNotHaveSimpleName("package-info")
            .should().dependOnClassesThat()
            .resideOutsideOfPackages("java..", "..answering.domain..", "..answering.port.in..");

    @ArchTest
    static final ArchRule OUTBOUND_PORT_DEPENDS_ONLY_ON_JDK_AND_DOMAIN = classes()
            .that().resideInAPackage("..answering.port.out..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..answering.domain..", "..answering.port.out..");
}
