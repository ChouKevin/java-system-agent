package com.java.system.agent.codebase.executor;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.capability.catalog.BuiltInCapabilityCatalog;
import com.java.system.agent.capability.dispatch.CapabilityExecutorRegistry;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 五個 codebase executor 精確 descriptor 與單一 adapter operation 委派測試
 */
class CodebaseExecutorTest {

    @ParameterizedTest
    @MethodSource("executorCases")
    void delegatesTheExactInvocationOnceAndReturnsTheUnchangedAdapterResult(ExecutorCase executorCase) {
        CapabilityExecutionResult expected = new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        CapabilityInvocation invocation = new CapabilityInvocation(executorCase.descriptor(), List.of(), "question",
                Map.of(), RevisionVector.empty());
        executorCase.stubber().stub(executorCase.adapter(), invocation, expected);

        CapabilityExecutionResult actual = executorCase.executor().execute(invocation);

        assertThat(executorCase.executor().capability()).isSameAs(executorCase.descriptor());
        assertThat(actual).isSameAs(expected);
        executorCase.verifier().verify(executorCase.adapter(), invocation);
        verifyNoMoreInteractions(executorCase.adapter());
    }

    @Test
    void rejectsADescriptorThatDoesNotExactlyMatchTheExecutorWiring() {
        CapabilityDescriptor wired = descriptor("codebase.lookup-api-route");
        CapabilityDescriptor differentVersion = new CapabilityDescriptor("codebase.lookup-api-route", "v2",
                Set.of(CandidateKind.REPOSITORY), 0, 1, new CapabilityQuerySchema(List.of()));
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CapabilityExecutor executor = new LookupApiRouteExecutor(wired, adapter);

        assertThatThrownBy(() -> executor.execute(new CapabilityInvocation(differentVersion, List.of(), "question",
                Map.of(), RevisionVector.empty())))
                .isInstanceOf(CapabilityExecutionContractException.class);
        verifyNoMoreInteractions(adapter);
    }

    @Test
    void registersTheFiveConcreteExecutorsForTheCompleteBuiltInCatalog() {
        BuiltInCapabilityCatalog catalog = new BuiltInCapabilityCatalog();
        List<CapabilityDescriptor> descriptors = catalog.availableCapabilities();
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CapabilityExecutorRegistry registry = new CapabilityExecutorRegistry(catalog, List.of(
                new ListEntryPointsExecutor(descriptors.get(0), adapter),
                new LookupApiRouteExecutor(descriptors.get(1), adapter),
                new SuggestApiRouteExecutor(descriptors.get(2), adapter),
                new OutgoingCallGraphExecutor(descriptors.get(3), adapter),
                new IncomingCallGraphExecutor(descriptors.get(4), adapter)));

        assertThat(registry.executors().keySet()).containsExactlyElementsOf(descriptors);
    }

    private static Stream<ExecutorCase> executorCases() {
        return Stream.of(
                listEntryPoints(), lookupApiRoute(), suggestApiRoute(), outgoingCallGraph(), incomingCallGraph());
    }

    private static ExecutorCase listEntryPoints() {
        CapabilityDescriptor descriptor = descriptor("codebase.list-entry-points");
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        return new ExecutorCase(new ListEntryPointsExecutor(descriptor, adapter), descriptor, adapter,
                (mockAdapter, invocation, result) -> when(mockAdapter.listEntryPoints(invocation)).thenReturn(result),
                (mockAdapter, invocation) -> verify(mockAdapter).listEntryPoints(same(invocation)));
    }

    private static ExecutorCase lookupApiRoute() {
        CapabilityDescriptor descriptor = descriptor("codebase.lookup-api-route");
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        return new ExecutorCase(new LookupApiRouteExecutor(descriptor, adapter), descriptor, adapter,
                (mockAdapter, invocation, result) -> when(mockAdapter.lookupApiRoute(invocation)).thenReturn(result),
                (mockAdapter, invocation) -> verify(mockAdapter).lookupApiRoute(same(invocation)));
    }

    private static ExecutorCase suggestApiRoute() {
        CapabilityDescriptor descriptor = descriptor("codebase.suggest-api-route");
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        return new ExecutorCase(new SuggestApiRouteExecutor(descriptor, adapter), descriptor, adapter,
                (mockAdapter, invocation, result) -> when(mockAdapter.suggestApiRoute(invocation)).thenReturn(result),
                (mockAdapter, invocation) -> verify(mockAdapter).suggestApiRoute(same(invocation)));
    }

    private static ExecutorCase outgoingCallGraph() {
        CapabilityDescriptor descriptor = descriptor("codebase.outgoing-call-graph");
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        return new ExecutorCase(new OutgoingCallGraphExecutor(descriptor, adapter), descriptor, adapter,
                (mockAdapter, invocation, result) -> when(mockAdapter.outgoingCallGraph(invocation)).thenReturn(result),
                (mockAdapter, invocation) -> verify(mockAdapter).outgoingCallGraph(same(invocation)));
    }

    private static ExecutorCase incomingCallGraph() {
        CapabilityDescriptor descriptor = descriptor("codebase.incoming-call-graph");
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        return new ExecutorCase(new IncomingCallGraphExecutor(descriptor, adapter), descriptor, adapter,
                (mockAdapter, invocation, result) -> when(mockAdapter.incomingCallGraph(invocation)).thenReturn(result),
                (mockAdapter, invocation) -> verify(mockAdapter).incomingCallGraph(same(invocation)));
    }

    private static CapabilityDescriptor descriptor(String name) {
        return new CapabilityDescriptor(name, "v1", Set.of(CandidateKind.REPOSITORY), 0, 1,
                new CapabilityQuerySchema(List.of()));
    }

    private record ExecutorCase(CapabilityExecutor executor, CapabilityDescriptor descriptor,
                                JavaSemanticServiceHttpAdapter adapter,
                                AdapterStubber stubber, AdapterVerifier verifier) {
    }

    @FunctionalInterface
    private interface AdapterStubber {

        void stub(JavaSemanticServiceHttpAdapter adapter, CapabilityInvocation invocation,
                  CapabilityExecutionResult result);
    }

    @FunctionalInterface
    private interface AdapterVerifier {

        void verify(JavaSemanticServiceHttpAdapter adapter, CapabilityInvocation invocation);
    }
}
