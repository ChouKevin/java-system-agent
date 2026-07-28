package com.java.system.agent.slack;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Slack transport 模組依賴邊界測試
 */
@AnalyzeClasses(packages = "com.java.system.agent", importOptions = ImportOption.DoNotIncludeTests.class)
class SlackModuleArchitectureTest {

    @ArchTest
    static final ArchRule nonSlackModulesDoNotDependOnSlackSdk = noClasses()
            .that().resideInAnyPackage(
                    "..runtime..", "..inbox..", "..persistence..", "..model..", "..capability..", "..codebase..", "..worker..")
            .should().dependOnClassesThat().resideInAnyPackage("com.slack.api..");
}
