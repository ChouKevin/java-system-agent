package com.java.system.agent.codebase.executor;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.codebase.planning.EntryPointType;
import com.java.system.agent.codebase.planning.IncomingCallGraphExecutionInput;
import com.java.system.agent.codebase.planning.ListEntryPointsExecutionInput;
import com.java.system.agent.codebase.planning.LookupApiRouteExecutionInput;
import com.java.system.agent.codebase.planning.OutgoingCallGraphExecutionInput;
import com.java.system.agent.codebase.planning.SuggestApiRouteExecutionInput;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 五個 codebase executor 將型別化 input 原樣委派給 Java Semantic Service adapter 的測試
 */
class CodebaseExecutorTest {

    private static final CapabilityExecutionResult RESULT = new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());

    @Test
    void delegatesListEntryPointsWithTypedInput() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CapabilityExecutionContext context = context("codebase_list_entry_points");
        ListEntryPointsExecutionInput input = new ListEntryPointsExecutionInput(EntryPointType.API);
        when(adapter.listEntryPoints(context, input)).thenReturn(RESULT);

        assertThat(new ListEntryPointsExecutor(adapter).execute(context, input)).isSameAs(RESULT);
        verify(adapter).listEntryPoints(same(context), same(input));
    }

    @Test
    void delegatesLookupApiRouteWithTypedInput() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CapabilityExecutionContext context = context("codebase_lookup_api_route");
        LookupApiRouteExecutionInput input = new LookupApiRouteExecutionInput("/orders", "GET");
        when(adapter.lookupApiRoute(context, input)).thenReturn(RESULT);

        assertThat(new LookupApiRouteExecutor(adapter).execute(context, input)).isSameAs(RESULT);
        verify(adapter).lookupApiRoute(same(context), same(input));
    }

    @Test
    void delegatesSuggestApiRouteWithTypedInput() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CapabilityExecutionContext context = context("codebase_suggest_api_route");
        SuggestApiRouteExecutionInput input = new SuggestApiRouteExecutionInput("/orders", "GET", 3);
        when(adapter.suggestApiRoute(context, input)).thenReturn(RESULT);

        assertThat(new SuggestApiRouteExecutor(adapter).execute(context, input)).isSameAs(RESULT);
        verify(adapter).suggestApiRoute(same(context), same(input));
    }

    @Test
    void delegatesOutgoingCallGraphWithTypedInput() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CapabilityExecutionContext context = context("codebase_outgoing_call_graph");
        OutgoingCallGraphExecutionInput input = new OutgoingCallGraphExecutionInput(2);
        when(adapter.outgoingCallGraph(context, input)).thenReturn(RESULT);

        assertThat(new OutgoingCallGraphExecutor(adapter).execute(context, input)).isSameAs(RESULT);
        verify(adapter).outgoingCallGraph(same(context), same(input));
    }

    @Test
    void delegatesIncomingCallGraphWithTypedInput() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        CapabilityExecutionContext context = context("codebase_incoming_call_graph");
        IncomingCallGraphExecutionInput input = new IncomingCallGraphExecutionInput(2);
        when(adapter.incomingCallGraph(context, input)).thenReturn(RESULT);

        assertThat(new IncomingCallGraphExecutor(adapter).execute(context, input)).isSameAs(RESULT);
        verify(adapter).incomingCallGraph(same(context), same(input));
    }

    private static CapabilityExecutionContext context(String capabilityName) {
        return new CapabilityExecutionContext(
                new CapabilityPolicy(capabilityName, "v1", Set.of(CandidateKind.REPOSITORY), 0, 1),
                List.of(), "question", RevisionVector.empty());
    }
}
