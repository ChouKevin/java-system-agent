package com.java.system.agent;

import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.candidate.AnalysisCandidate;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
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

    private final ApplicationModules modules = ApplicationModules.of(Application.class);

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

        assertEquals(Set.of("runtime", "inbox", "persistence"), moduleNames,
                "Expected exactly the runtime, inbox, and persistence modules");
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
                        && domain.contains(CapabilityDescriptor.class)
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
}
