package com.java.system.agent.persistence;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * PostgreSQL persistence 與 framework-free 核心的最終模組邊界驗證
 */
@AnalyzeClasses(
        packages = "com.java.system.agent",
        importOptions = ImportOption.DoNotIncludeTests.class)
class PersistenceModuleArchitectureTest {

    @ArchTest
    static final ArchRule PERSISTENCE_DEPENDS_ONLY_ON_EXPOSED_CONTRACTS_AND_INFRASTRUCTURE = classes()
            .that().resideInAPackage("..persistence..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "com.fasterxml.jackson..",
                    "org.springframework.dao..",
                    "org.springframework.jdbc..",
                    "org.springframework.transaction..",
                    "..persistence..",
                    "..runtime.domain..",
                    "..runtime.port.out..",
                    "..inbox.domain..",
                    "..inbox.port.out..");

    @ArchTest
    static final ArchRule PERSISTENCE_DOES_NOT_DEPEND_ON_APPLICATION_INTERNALS = noClasses()
            .that().resideInAPackage("..persistence..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..runtime.application..", "..inbox.application..", "..inbox.port.in..");

    @ArchTest
    static final ArchRule FRAMEWORK_FREE_MODULES_DO_NOT_DEPEND_ON_JDBC = noClasses()
            .that().resideInAnyPackage("..runtime..", "..inbox..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..");

    @ArchTest
    static final ArchRule CORE_AND_PERSISTENCE_CLASSES_HAVE_NO_SPRING_STEREOTYPES = noClasses()
            .that().resideInAnyPackage("..runtime..", "..inbox..", "..persistence..")
            .should().beAnnotatedWith(Component.class)
            .orShould().beAnnotatedWith(Service.class)
            .orShould().beAnnotatedWith(Repository.class)
            .orShould().beAnnotatedWith(Configuration.class);

    @ArchTest
    static final ArchRule CORE_AND_PERSISTENCE_METHODS_DECLARE_NO_SPRING_BEANS = noMethods()
            .that().areDeclaredInClassesThat()
            .resideInAnyPackage("..runtime..", "..inbox..", "..persistence..")
            .should().beAnnotatedWith(Bean.class);

    @ArchTest
    static final ArchRule SOURCE_TREE_HAS_NO_TARGET_PACKAGE = noClasses()
            .should().resideInAPackage("..target..");
}
