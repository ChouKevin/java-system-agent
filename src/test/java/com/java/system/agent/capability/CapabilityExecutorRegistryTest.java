package com.java.system.agent.capability;

import com.java.system.agent.capability.catalog.BuiltInCapabilityCatalog;
import com.java.system.agent.capability.dispatch.CapabilityExecutionDispatcher;
import com.java.system.agent.capability.dispatch.CapabilityExecutorRegistry;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.runtime.domain.capability.ArgumentDefinition;
import com.java.system.agent.runtime.domain.capability.ArgumentType;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.CapabilityCatalogPort;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Capability executor registry 的目錄完整性與精確 dispatch 邊界測試
 */
class CapabilityExecutorRegistryTest {

    @Test
    void registersEveryBuiltInDescriptorInCatalogOrder() {
        BuiltInCapabilityCatalog catalog = new BuiltInCapabilityCatalog();
        List<CapabilityDescriptor> descriptors = catalog.availableCapabilities();
        CapabilityExecutorRegistry registry = new CapabilityExecutorRegistry(
                catalog, descriptors.stream().map(SucceedingExecutor::new).toList());

        assertThat(descriptors).containsExactly(
                descriptor(
                        "codebase.list-entry-points", Set.of(CandidateKind.REPOSITORY), 1, 1,
                        new ArgumentDefinition("type", ArgumentType.ENUM, false, null, null,
                                Set.of("API", "MQ", "SCHEDULE"))),
                descriptor(
                        "codebase.lookup-api-route", Set.of(CandidateKind.REPOSITORY), 0, 1,
                        new ArgumentDefinition("apiPath", ArgumentType.TEXT, true, null, null, Set.of()),
                        new ArgumentDefinition("httpMethod", ArgumentType.TEXT, false, null, null, Set.of())),
                descriptor(
                        "codebase.suggest-api-route", Set.of(CandidateKind.REPOSITORY), 0, 1,
                        new ArgumentDefinition("apiPath", ArgumentType.TEXT, true, null, null, Set.of()),
                        new ArgumentDefinition("httpMethod", ArgumentType.TEXT, false, null, null, Set.of()),
                        new ArgumentDefinition("limit", ArgumentType.INTEGER, true, 1, 20, Set.of())),
                descriptor(
                        "codebase.outgoing-call-graph", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1,
                        new ArgumentDefinition("depth", ArgumentType.INTEGER, false, 1, 2, Set.of())),
                descriptor(
                        "codebase.incoming-call-graph", Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1,
                        new ArgumentDefinition("depth", ArgumentType.INTEGER, false, 1, 2, Set.of())));
        assertThat(registry.executors().keySet()).containsExactlyElementsOf(descriptors);
        assertThat(registry.executors()).isUnmodifiable();
    }

    @Test
    void rejectsDuplicateMissingAndUndeclaredExecutors() {
        CapabilityDescriptor first = descriptor("first");
        CapabilityDescriptor second = descriptor("second");
        CapabilityDescriptor undeclared = descriptor("undeclared");
        CapabilityCatalogPort catalog = () -> List.of(first, second);

        assertThatThrownBy(() -> new CapabilityExecutorRegistry(
                () -> List.of(first, first), List.of(new SucceedingExecutor(first))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate capability descriptor");
        assertThatThrownBy(() -> new CapabilityExecutorRegistry(
                catalog, List.of(new SucceedingExecutor(first), new SucceedingExecutor(first))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate executor");
        assertThatThrownBy(() -> new CapabilityExecutorRegistry(catalog, List.of(new SucceedingExecutor(first))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog descriptors");
        assertThatThrownBy(() -> new CapabilityExecutorRegistry(
                catalog, List.of(new SucceedingExecutor(first), new SucceedingExecutor(undeclared))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog descriptors");
    }

    @Test
    void dispatchesOnlyTheExactRegisteredDescriptorWithoutFallback() {
        CapabilityDescriptor registered = descriptor("codebase.lookup-api-route");
        CapabilityDescriptor similar = descriptor("codebase.lookup-api-route-v2");
        CapabilityExecutorRegistry registry = new CapabilityExecutorRegistry(
                () -> List.of(registered), List.of(new SucceedingExecutor(registered)));
        CapabilityExecutionDispatcher dispatcher = new CapabilityExecutionDispatcher(registry);

        assertThat(dispatcher.execute(invocation(registered))).isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThatThrownBy(() -> dispatcher.execute(invocation(similar)))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessage("validated capability has no registered executor");
    }

    @Test
    void rejectsAnExecutorThatReturnsNoResult() {
        CapabilityDescriptor descriptor = descriptor("codebase.lookup-api-route");
        CapabilityExecutorRegistry registry = new CapabilityExecutorRegistry(
                () -> List.of(descriptor), List.of(new NullResultExecutor(descriptor)));
        CapabilityExecutionDispatcher dispatcher = new CapabilityExecutionDispatcher(registry);

        assertThatThrownBy(() -> dispatcher.execute(invocation(descriptor)))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessage("capability executor must return a result");
    }

    private static CapabilityDescriptor descriptor(String name) {
        return descriptor(name, Set.of(CandidateKind.REPOSITORY), 0, 1);
    }

    private static CapabilityDescriptor descriptor(
            String name,
            Set<CandidateKind> candidateKinds,
            int minimumCandidates,
            int maximumCandidates,
            ArgumentDefinition... arguments) {
        return new CapabilityDescriptor(
                name,
                "v1",
                candidateKinds,
                minimumCandidates,
                maximumCandidates,
                new CapabilityQuerySchema(List.of(arguments)));
    }

    private static CapabilityInvocation invocation(CapabilityDescriptor descriptor) {
        return new CapabilityInvocation(descriptor, List.of(), "Trace this capability", Map.of(), RevisionVector.empty());
    }

    private record SucceedingExecutor(CapabilityDescriptor capability) implements CapabilityExecutor {

        @Override
        public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
            return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        }
    }

    private record NullResultExecutor(CapabilityDescriptor capability) implements CapabilityExecutor {

        @Override
        public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
            return null;
        }
    }
}
