package com.java.system.agent.persistence;

import com.java.system.agent.AgentCapabilityConfiguration;
import com.java.system.agent.AgentCodebaseConfiguration;
import com.java.system.agent.AgentCodebaseProperties;
import com.java.system.agent.AgentModelConfiguration;
import com.java.system.agent.AgentRuntimeConfiguration;
import com.java.system.agent.AgentRuntimeProperties;
import com.java.system.agent.Application;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.util.Set;

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

    private static final Set<String> ROOT_BOOTSTRAP_TYPES = Set.of(
            Application.class.getName(),
            AgentCapabilityConfiguration.class.getName(),
            AgentCodebaseConfiguration.class.getName(),
            AgentCodebaseProperties.class.getName(),
            AgentModelConfiguration.class.getName(),
            AgentRuntimeConfiguration.class.getName(),
            AgentRuntimeProperties.class.getName());

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
    static final ArchRule AGENT_MODULE_CLASSES_HAVE_NO_SPRING_STEREOTYPES = noClasses()
            .that().resideInAnyPackage("..runtime..", "..inbox..", "..persistence..", "..capability..", "..model..", "..codebase..")
            .should().beAnnotatedWith(Component.class)
            .orShould().beAnnotatedWith(Service.class)
            .orShould().beAnnotatedWith(Repository.class)
            .orShould().beAnnotatedWith(Configuration.class);

    @ArchTest
    static final ArchRule AGENT_MODULE_METHODS_DECLARE_NO_SPRING_BEANS = noMethods()
            .that().areDeclaredInClassesThat()
            .resideInAnyPackage("..runtime..", "..inbox..", "..persistence..", "..capability..", "..model..", "..codebase..")
            .should().beAnnotatedWith(Bean.class);

    @ArchTest
    static final ArchRule ROOT_PACKAGE_REMAINS_EXPLICIT_BOOTSTRAP = classes()
            .that().resideInAPackage("com.java.system.agent")
            .should(new ArchCondition<>("be an approved root bootstrap type") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    if (!ROOT_BOOTSTRAP_TYPES.contains(javaClass.getFullName())) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                javaClass.getFullName() + " is not an approved root bootstrap type"));
                    }
                }
            });

    @ArchTest
    static final ArchRule SOURCE_TREE_HAS_NO_TARGET_PACKAGE = noClasses()
            .should().resideInAPackage("..target..");
}
