package com.java.system.agent;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.scope.RepositoryId;
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

        assertEquals(Set.of("answering", "interaction", "persistence", "capability", "model", "codeintelligence", "slack", "worker"), moduleNames,
                "Expected exactly the answering, interaction, persistence, capability, model, code intelligence, slack, and worker modules");
    }

    @Test
    @DisplayName("agent v2 answering kernel should depend on no other module")
    void answeringKernelShouldHaveNoModuleDependencies() {
        ApplicationModule answering = requireAnswering();

        assertTrue(answering.getDirectDependencies(modules).isEmpty(),
                () -> "Expected answering kernel to have no module dependencies, but found: "
                        + answering.getDirectDependencies(modules).uniqueModules().toList());
    }

    @Test
    @DisplayName("answering kernel should expose only its contract packages")
    void answeringKernelShouldExposeOnlyContractPackages() {
        Set<String> exposed = requireAnswering().getNamedInterfaces().stream()
                .filter(namedInterface -> !namedInterface.isUnnamed())
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("domain", "port-in", "port-out"), exposed,
                "Answering kernel must expose exactly its domain and port packages");
    }

    @Test
    @DisplayName("answering domain interface should contain shared domain values")
    void answeringDomainInterfaceShouldContainSharedDomainValues() {
        NamedInterface domain = requireAnswering().getNamedInterfaces().getByName("domain")
                .orElseThrow(() -> new IllegalStateException("Missing answering domain interface"));

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
                "Answering domain interface must expose all domain values shared with adapters");
    }

    @Test
    @DisplayName("durable session interaction should depend only on answering")
    void interactionShouldHaveOnlyIntendedModuleDependencies() {
        ApplicationModule interactionModule = requireInteraction();
        Set<String> dependencies = interactionModule.getDirectDependencies(modules).uniqueModules()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());

        assertEquals(Set.of("answering"), dependencies,
                "Interaction module must depend only on answering exposed contracts");
    }

    @Test
    @DisplayName("durable session interaction should expose only its contract packages")
    void interactionShouldExposeOnlyContractPackages() {
        Set<String> exposed = requireInteraction().getNamedInterfaces().stream()
                .filter(namedInterface -> !namedInterface.isUnnamed())
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("domain", "port-in", "port-out"), exposed,
                "Interaction module must expose exactly its domain and port packages");
    }

    @Test
    @DisplayName("PostgreSQL persistence should depend only on exposed answering and interaction contracts")
    void persistenceShouldHaveOnlyIntendedModuleDependencies() {
        ApplicationModule persistence = requirePersistence();
        Set<String> dependencies = persistence.getDirectDependencies(modules).uniqueModules()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());

        assertEquals(Set.of("answering", "interaction"), dependencies,
                "Persistence module must depend only on answering and interaction exposed contracts");
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
    @DisplayName("capability module should declare only answering domain and outbound dependencies")
    void capabilityShouldDeclareOnlyIntendedModuleDependencies() {
        assertEquals(Set.of("answering :: domain", "answering :: port-out"), allowedDependenciesOf(requireCapability()),
                "Capability module must depend only on answering domain and outbound contracts");
        assertEquals(Set.of("answering"), directDependenciesOf(requireCapability()),
                "Capability module must directly depend only on answering");
    }

    @Test
    @DisplayName("model module should declare only answering and capability planning dependencies")
    void modelShouldDeclareOnlyIntendedModuleDependencies() {
        assertEquals(Set.of("answering :: domain", "answering :: port-out", "capability :: planning"),
                allowedDependenciesOf(requireModel()),
                "Model module must depend only on answering contracts and capability planning registry");
        assertEquals(Set.of("answering", "capability"), directDependenciesOf(requireModel()),
                "Model module must directly depend only on answering and capability");
    }

    @Test
    @DisplayName("code intelligence module should declare only answering, capability executor, and planning dependencies")
    void codeIntelligenceShouldDeclareOnlyIntendedModuleDependencies() {
        assertEquals(Set.of("answering :: domain", "answering :: port-out", "capability :: executor-spi", "capability :: planning"),
                allowedDependenciesOf(requireCodeIntelligence()),
                "Code intelligence module must depend only on answering contracts, capability executor SPI, and planning contract");
        assertEquals(Set.of("answering", "capability"), directDependenciesOf(requireCodeIntelligence()),
                "Code intelligence module must directly depend only on answering and capability");
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
    @DisplayName("Slack transport should declare only interaction contracts and answering domain values")
    void slackShouldDeclareOnlyIntendedModuleDependencies() {
        assertEquals(Set.of("interaction :: domain", "interaction :: port-in", "interaction :: port-out", "answering :: domain"),
                allowedDependenciesOf(requireSlack()),
                "Slack must depend only on interaction contracts and transport-neutral answering domain values");
        assertEquals(Set.of("interaction", "answering"), directDependenciesOf(requireSlack()),
                "Slack must directly depend only on interaction and answering");
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

    private ApplicationModule requireAnswering() {
        return modules.getModuleByName("answering")
                .orElseThrow(() -> new IllegalStateException("Missing module: answering"));
    }

    private ApplicationModule requireInteraction() {
        return modules.getModuleByName("interaction")
                .orElseThrow(() -> new IllegalStateException("Missing module: interaction"));
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

    private ApplicationModule requireCodeIntelligence() {
        return modules.getModuleByName("codeintelligence")
                .orElseThrow(() -> new IllegalStateException("Missing module: code intelligence"));
    }

    private ApplicationModule requireSlack() {
        return modules.getModuleByName("slack")
                .orElseThrow(() -> new IllegalStateException("Missing module: slack"));
    }
}
