package com.java.system.agent.inbox;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Durable session inbox 對 runtime 與基礎設施的模組邊界測試
 */
@AnalyzeClasses(
        packages = "com.java.system.agent.inbox",
        importOptions = ImportOption.DoNotIncludeTests.class)
class InboxModuleArchitectureTest {

    @ArchTest
    static final ArchRule SOURCE_ADMISSION_AND_DELIVERY_CONTRACTS_REMAIN_IN_EXPOSED_INBOX_PACKAGES = classes()
            .that().haveFullyQualifiedName("com.java.system.agent.inbox.domain.NormalizedSourceEvent")
            .or().haveFullyQualifiedName("com.java.system.agent.inbox.domain.SourceAcceptance")
            .or().haveFullyQualifiedName("com.java.system.agent.inbox.domain.delivery.DeliveryMessage")
            .or().haveFullyQualifiedName("com.java.system.agent.inbox.port.in.AcceptSourceEventUseCase")
            .or().haveFullyQualifiedName("com.java.system.agent.inbox.port.out.SourceAcceptancePort")
            .or().haveFullyQualifiedName("com.java.system.agent.inbox.port.out.DeliveryOutboxPort")
            .or().haveFullyQualifiedName("com.java.system.agent.inbox.port.out.DeliveryTransportPort")
            .should().resideInAnyPackage("..inbox.domain..", "..inbox.port.in..", "..inbox.port.out..");

    @ArchTest
    static final ArchRule DOMAIN_DEPENDS_ONLY_ON_JDK_INBOX_DOMAIN_AND_RUNTIME_DOMAIN = classes()
            .that().resideInAPackage("..inbox.domain..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..inbox.domain..", "..runtime.domain..");

    @ArchTest
    static final ArchRule APPLICATION_DEPENDS_ONLY_ON_INBOX_CONTRACTS_AND_RUNTIME_EXPOSED_INTERFACES = classes()
            .that().resideInAPackage("..inbox.application..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "..inbox.application..",
                    "..inbox.domain..",
                    "..inbox.port.in..",
                    "..inbox.port.out..",
                    "..runtime.domain..",
                    "..runtime.port.in..");

    @ArchTest
    static final ArchRule INBOX_DOES_NOT_DEPEND_ON_SPRING_PERSISTENCE_SLACK_OR_RUNTIME_INTERNALS = noClasses()
            .that().resideInAPackage("..inbox..")
            .and().doNotHaveSimpleName("package-info")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "javax.persistence..",
                    "java.sql..",
                    "com.slack..",
                    "..slack..",
                    "..runtime.application..");
}
