package com.java.system.agent;

import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.candidate.AnalysisCandidate;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationModularityTests {

    private static final DescribedPredicate<JavaClass> PRIVILEGED_BOOTSTRAP_TYPES =
            JavaClass.Predicates.belongToAnyOf(
                    AgentCapabilityConfiguration.class,
                    AgentObservabilityConfiguration.class,
                    AgentCodebaseConfiguration.class,
                    AgentCodebaseProperties.class,
                    AgentModelConfiguration.class,
                    AgentModelRateLimitProperties.class,
                    AgentPersistenceConfiguration.class,
                    AgentDatabaseProperties.class,
                    AgentRuntimeConfiguration.class,
                    AgentRuntimeProperties.class,
                    AgentWorkerProperties.class,
                    SlackAgentConfiguration.class,
                    SlackAgentProperties.class);

    private final ApplicationModules modules = ApplicationModules.of(Application.class, PRIVILEGED_BOOTSTRAP_TYPES);

    @Test
    @DisplayName("modulith constraints should pass")
    void verifyModularStructure() {
        modules.verify();
    }

    @Test
    @DisplayName("the root should contain exactly the implemented modules")
    void shouldContainExactlyTheImplementedModules() {
        Set<String> moduleNames = modules.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());

        assertEquals(Set.of("runtime", "inbox", "persistence", "capability", "model", "codebase", "slack", "worker"), moduleNames,
                "Expected exactly the runtime, inbox, persistence, capability, model, codebase, slack, and worker modules");
    }

    @Test
    @DisplayName("agent v2 runtime kernel should depend on no other module")
    void runtimeKernelShouldHaveNoModuleDependencies() {
        ApplicationModule runtime = requireRuntime();

        assertTrue(runtime.getDirectDependencies(modules).isEmpty(),
                () -> "Expected runtime kernel to have no module dependencies, but found: "
                        + runtime.getDirectDependencies(modules).uniqueModules().toList());
    }

    @Test
    @DisplayName("runtime kernel should expose only its contract packages")
    void runtimeKernelShouldExposeOnlyContractPackages() {
        Set<String> exposed = requireRuntime().getNamedInterfaces().stream()
                .filter(namedInterface -> !namedInterface.isUnnamed())
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("domain", "port-in", "port-out"), exposed,
                "Runtime kernel must expose exactly its domain and port packages");
    }

    @Test
    @DisplayName("runtime domain interface should contain shared domain values")
    void runtimeDomainInterfaceShouldContainSharedDomainValues() {
        NamedInterface domain = requireRuntime().getNamedInterfaces().getByName("domain")
                .orElseThrow(() -> new IllegalStateException("Missing runtime domain interface"));

        assertTrue(domain.contains(SessionId.class)
                        && domain.contains(AnalysisRunId.class)
                        && domain.contains(AttemptBudget.class)
                        && domain.contains(AgentAction.class)
                        && domain.contains(AnswerDocument.class)
                        && domain.contains(AnalysisCandidate.class)
                        && domain.contains(CapabilityPolicy.class)
                        && domain.contains(EvidenceRef.class)
                        && domain.contains(CandidateHandle.class)
                        && domain.contains(AgentObservation.class)
                        && domain.contains(RepositoryId.class),
                "Runtime domain interface must expose all domain values shared with adapters");
    }

    @Test
    @DisplayName("durable session inbox should depend only on runtime")
    void inboxShouldHaveOnlyIntendedModuleDependencies() {
        ApplicationModule inbox = requireInbox();
        Set<String> dependencies = inbox.getDirectDependencies(modules).uniqueModules()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());

        assertEquals(Set.of("runtime"), dependencies,
                "Inbox module must depend only on runtime exposed contracts");
    }

    @Test
    @DisplayName("durable session inbox should expose only its contract packages")
    void inboxShouldExposeOnlyContractPackages() {
        Set<String> exposed = requireInbox().getNamedInterfaces().stream()
                .filter(namedInterface -> !namedInterface.isUnnamed())
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("domain", "port-in", "port-out"), exposed,
                "Inbox module must expose exactly its domain and port packages");
    }

    @Test
    @DisplayName("PostgreSQL persistence should depend only on exposed runtime and inbox contracts")
    void persistenceShouldHaveOnlyIntendedModuleDependencies() {
        ApplicationModule persistence = requirePersistence();
        Set<String> dependencies = persistence.getDirectDependencies(modules).uniqueModules()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());

        assertEquals(Set.of("runtime", "inbox"), dependencies,
                "Persistence module must depend only on runtime and inbox exposed contracts");
    }

    @Test
    @DisplayName("PostgreSQL persistence should expose no implementation package")
    void persistenceShouldExposeNoNamedInterface() {
        Set<String> exposed = requirePersistence().getNamedInterfaces().stream()
                .filter(namedInterface -> !namedInterface.isUnnamed())
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());

        assertTrue(exposed.isEmpty(), "Persistence implementation packages must remain internal");
    }

    @Test
    @DisplayName("capability module should declare only runtime domain and outbound dependencies")
    void capabilityShouldDeclareOnlyIntendedModuleDependencies() {
        assertEquals(Set.of("runtime :: domain", "runtime :: port-out"), allowedDependenciesOf(requireCapability()),
                "Capability module must depend only on runtime domain and outbound contracts");
        assertEquals(Set.of("runtime"), directDependenciesOf(requireCapability()),
                "Capability module must directly depend only on runtime");
    }

    @Test
    @DisplayName("model module should declare only runtime and capability planning dependencies")
    void modelShouldDeclareOnlyIntendedModuleDependencies() {
        assertEquals(Set.of("runtime :: domain", "runtime :: port-out", "capability :: planning"),
                allowedDependenciesOf(requireModel()),
                "Model module must depend only on runtime contracts and capability planning registry");
        assertEquals(Set.of("runtime", "capability"), directDependenciesOf(requireModel()),
                "Model module must directly depend only on runtime and capability");
    }

    @Test
    @DisplayName("codebase module should declare only runtime, capability executor, and planning dependencies")
    void codebaseShouldDeclareOnlyIntendedModuleDependencies() {
        assertEquals(Set.of("runtime :: domain", "runtime :: port-out", "capability :: executor-spi", "capability :: planning"),
                allowedDependenciesOf(requireCodebase()),
                "Codebase module must depend only on runtime contracts, capability executor SPI, and planning contract");
        assertEquals(Set.of("runtime", "capability"), directDependenciesOf(requireCodebase()),
                "Codebase module must directly depend only on runtime and capability");
    }

    @Test
    @DisplayName("capability module should expose executor SPI and unified planning registry")
    void capabilityShouldExposeExecutorSpi() {
        Set<String> exposed = requireCapability().getNamedInterfaces().stream()
                .filter(namedInterface -> !namedInterface.isUnnamed())
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("executor-spi", "planning"), exposed,
                "Capability module must expose exactly its executor SPI and unified planning registry");
    }

    @Test
    @DisplayName("Slack transport should declare only inbox contracts and runtime domain values")
    void slackShouldDeclareOnlyIntendedModuleDependencies() {
        assertEquals(Set.of("inbox :: domain", "inbox :: port-in", "inbox :: port-out", "runtime :: domain"),
                allowedDependenciesOf(requireSlack()),
                "Slack must depend only on inbox contracts and transport-neutral runtime domain values");
        assertEquals(Set.of("inbox", "runtime"), directDependenciesOf(requireSlack()),
                "Slack must directly depend only on inbox and runtime");
    }

    @Test
    @DisplayName("Slack transport should expose no implementation package")
    void slackShouldExposeNoNamedInterface() {
        Set<String> exposed = requireSlack().getNamedInterfaces().stream()
                .filter(namedInterface -> !namedInterface.isUnnamed())
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());

        assertTrue(exposed.isEmpty(), "Slack implementation packages must remain internal");
    }

    private Set<String> allowedDependenciesOf(ApplicationModule module) {
        return module.getAllowedDependencies(modules).stream()
                .map(dependency -> dependency.getTargetModule().getIdentifier().toString()
                        + " :: " + dependency.getTargetNamedInterface().getName())
                .collect(Collectors.toSet());
    }

    private Set<String> directDependenciesOf(ApplicationModule module) {
        return module.getDirectDependencies(modules).uniqueModules()
                .map(dependency -> dependency.getIdentifier().toString())
                .collect(Collectors.toSet());
    }

    private ApplicationModule requireRuntime() {
        return modules.getModuleByName("runtime")
                .orElseThrow(() -> new IllegalStateException("Missing module: runtime"));
    }

    private ApplicationModule requireInbox() {
        return modules.getModuleByName("inbox")
                .orElseThrow(() -> new IllegalStateException("Missing module: inbox"));
    }

    private ApplicationModule requirePersistence() {
        return modules.getModuleByName("persistence")
                .orElseThrow(() -> new IllegalStateException("Missing module: persistence"));
    }

    private ApplicationModule requireCapability() {
        return modules.getModuleByName("capability")
                .orElseThrow(() -> new IllegalStateException("Missing module: capability"));
    }

    private ApplicationModule requireModel() {
        return modules.getModuleByName("model")
                .orElseThrow(() -> new IllegalStateException("Missing module: model"));
    }

    private ApplicationModule requireCodebase() {
        return modules.getModuleByName("codebase")
                .orElseThrow(() -> new IllegalStateException("Missing module: codebase"));
    }

    private ApplicationModule requireSlack() {
        return modules.getModuleByName("slack")
                .orElseThrow(() -> new IllegalStateException("Missing module: slack"));
    }
}
