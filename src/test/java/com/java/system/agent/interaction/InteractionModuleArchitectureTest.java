package com.java.system.agent.interaction;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Durable session interaction 對 answering 與基礎設施的模組邊界測試
 */
@AnalyzeClasses(
        packages = "com.java.system.agent.interaction",
        importOptions = ImportOption.DoNotIncludeTests.class)
class InteractionModuleArchitectureTest {

    @ArchTest
    static final ArchRule SOURCE_ADMISSION_AND_DELIVERY_CONTRACTS_REMAIN_IN_EXPOSED_INTERACTION_PACKAGES = classes()
            .that().haveFullyQualifiedName("com.java.system.agent.interaction.domain.NormalizedSourceEvent")
            .or().haveFullyQualifiedName("com.java.system.agent.interaction.domain.SourceAcceptance")
            .or().haveFullyQualifiedName("com.java.system.agent.interaction.domain.delivery.DeliveryMessage")
            .or().haveFullyQualifiedName("com.java.system.agent.interaction.port.in.AcceptSourceEventUseCase")
            .or().haveFullyQualifiedName("com.java.system.agent.interaction.port.out.SourceAcceptancePort")
            .or().haveFullyQualifiedName("com.java.system.agent.interaction.port.out.DeliveryOutboxPort")
            .or().haveFullyQualifiedName("com.java.system.agent.interaction.port.out.DeliveryTransportPort")
            .should().resideInAnyPackage("..interaction.domain..", "..interaction.port.in..", "..interaction.port.out..");

    @ArchTest
    static final ArchRule DOMAIN_DEPENDS_ONLY_ON_JDK_INTERACTION_DOMAIN_AND_RUNTIME_DOMAIN = classes()
            .that().resideInAPackage("..interaction.domain..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..interaction.domain..", "..answering.domain..");

    @ArchTest
    static final ArchRule APPLICATION_DEPENDS_ONLY_ON_INTERACTION_CONTRACTS_AND_RUNTIME_EXPOSED_INTERFACES = classes()
            .that().resideInAPackage("..interaction.application..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "..interaction.application..",
                    "..interaction.domain..",
                    "..interaction.port.in..",
                    "..interaction.port.out..",
                    "..answering.domain..",
                    "..answering.port.in..");

    @ArchTest
    static final ArchRule INTERACTION_DOES_NOT_DEPEND_ON_SPRING_PERSISTENCE_SLACK_OR_RUNTIME_INTERNALS = noClasses()
            .that().resideInAPackage("..interaction..")
            .and().doNotHaveSimpleName("package-info")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "javax.persistence..",
                    "java.sql..",
                    "com.slack..",
                    "..slack..",
                    "..answering.application..");

    @ArchTest
    static final ArchRule INTERACTION_OUTBOUND_PORTS_DO_NOT_DEPEND_ON_ANSWERING_INBOUND_PORTS = noClasses()
            .that().resideInAPackage("..interaction.port.out..")
            .should().dependOnClassesThat()
            .resideInAPackage("..answering.port.in..");
}
