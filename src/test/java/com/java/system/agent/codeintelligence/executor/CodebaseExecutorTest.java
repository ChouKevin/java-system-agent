package com.java.system.agent.codeintelligence.executor;

import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.codeintelligence.planning.EntryPointType;
import com.java.system.agent.codeintelligence.planning.DiscoverConceptsExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverEventListenersExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverMethodImplementationsExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverTypeMembersExecutionInput;
import com.java.system.agent.codeintelligence.planning.FindInternalReferencesExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetEvidenceSourceExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetMethodSourceExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetSourceSegmentExecutionInput;
import com.java.system.agent.codeintelligence.planning.IncomingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.ListEntryPointsExecutionInput;
import com.java.system.agent.codeintelligence.planning.LookupApiRouteExecutionInput;
import com.java.system.agent.codeintelligence.planning.OutgoingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.SuggestApiRouteExecutionInput;
import com.java.system.agent.codeintelligence.planning.ResolveConceptExecutionInput;
import com.java.system.agent.codeintelligence.planning.ResolveSourceSymbolExecutionInput;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 五個 Code intelligence executor 將型別化 input 原樣委派給 Java Semantic Service adapter 的測試
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

    @Test
    void delegatesEveryTypedDiscoveryInputExactlyOnce() {
        JavaSemanticServiceHttpAdapter adapter = mock(JavaSemanticServiceHttpAdapter.class);
        SemanticDtos.MethodTargetPayload target = target();
        SemanticDtos.ConceptFollowUpIdentity concept = conceptIdentity();
        SemanticDtos.EvidenceSourceFollowUpIdentity evidence = evidenceIdentity();
        SemanticDtos.SourceRangePayload range = range();
        DiscoverConceptsExecutionInput concepts = new DiscoverConceptsExecutionInput(List.of(
                new DiscoverConceptsExecutionInput.Term("order", "TOKEN_EXACT")), List.of("TYPE"), Optional.empty(), 0, 50);
        DiscoverEventListenersExecutionInput listeners = new DiscoverEventListenersExecutionInput("com.example.Event", 0, 50);
        DiscoverMethodImplementationsExecutionInput implementations = new DiscoverMethodImplementationsExecutionInput(
                Optional.of(target));
        ResolveConceptExecutionInput resolveConcept = new ResolveConceptExecutionInput(concept);
        DiscoverTypeMembersExecutionInput members = new DiscoverTypeMembersExecutionInput(target.sourceType(),
                List.of("METHOD"), Optional.empty(), 0, 50);
        FindInternalReferencesExecutionInput references = new FindInternalReferencesExecutionInput(
                new SemanticDtos.InternalReferenceFollowUpTarget("METHOD", target), 0, 50);
        GetEvidenceSourceExecutionInput evidenceSource = new GetEvidenceSourceExecutionInput(evidence);
        GetMethodSourceExecutionInput methodSource = new GetMethodSourceExecutionInput(Optional.of(target));
        GetSourceSegmentExecutionInput segment = new GetSourceSegmentExecutionInput(range, 1);
        ResolveSourceSymbolExecutionInput sourceSymbol = new ResolveSourceSymbolExecutionInput("order", Optional.empty(),
                Optional.empty());
        CapabilityExecutionContext context = context("codebase_discover_concepts");
        when(adapter.discoverConcepts(context, concepts)).thenReturn(RESULT);
        when(adapter.resolveConcept(context, resolveConcept)).thenReturn(RESULT);
        when(adapter.discoverEventListeners(context, listeners)).thenReturn(RESULT);
        when(adapter.discoverMethodImplementations(context, implementations)).thenReturn(RESULT);
        when(adapter.discoverTypeMembers(context, members)).thenReturn(RESULT);
        when(adapter.findInternalReferences(context, references)).thenReturn(RESULT);
        when(adapter.getEvidenceSource(context, evidenceSource)).thenReturn(RESULT);
        when(adapter.getMethodSource(context, methodSource)).thenReturn(RESULT);
        when(adapter.getSourceSegment(context, segment)).thenReturn(RESULT);
        when(adapter.resolveSourceSymbol(context, sourceSymbol)).thenReturn(RESULT);

        assertThat(new DiscoverConceptsExecutor(adapter).execute(context, concepts)).isSameAs(RESULT);
        assertThat(new ResolveConceptExecutor(adapter).execute(context, resolveConcept)).isSameAs(RESULT);
        assertThat(new DiscoverEventListenersExecutor(adapter).execute(context, listeners)).isSameAs(RESULT);
        assertThat(new DiscoverMethodImplementationsExecutor(adapter).execute(context, implementations)).isSameAs(RESULT);
        assertThat(new DiscoverTypeMembersExecutor(adapter).execute(context, members)).isSameAs(RESULT);
        assertThat(new FindInternalReferencesExecutor(adapter).execute(context, references)).isSameAs(RESULT);
        assertThat(new GetEvidenceSourceExecutor(adapter).execute(context, evidenceSource)).isSameAs(RESULT);
        assertThat(new GetMethodSourceExecutor(adapter).execute(context, methodSource)).isSameAs(RESULT);
        assertThat(new GetSourceSegmentExecutor(adapter).execute(context, segment)).isSameAs(RESULT);
        assertThat(new ResolveSourceSymbolExecutor(adapter).execute(context, sourceSymbol)).isSameAs(RESULT);

        verify(adapter).discoverConcepts(same(context), same(concepts));
        verify(adapter).resolveConcept(same(context), same(resolveConcept));
        verify(adapter).discoverEventListeners(same(context), same(listeners));
        verify(adapter).discoverMethodImplementations(same(context), same(implementations));
        verify(adapter).discoverTypeMembers(same(context), same(members));
        verify(adapter).findInternalReferences(same(context), same(references));
        verify(adapter).getEvidenceSource(same(context), same(evidenceSource));
        verify(adapter).getMethodSource(same(context), same(methodSource));
        verify(adapter).getSourceSegment(same(context), same(segment));
        verify(adapter).resolveSourceSymbol(same(context), same(sourceSymbol));
    }

    private static SemanticDtos.MethodTargetPayload target() {
        return new SemanticDtos.MethodTargetPayload(new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload("com.example", "Orders"), "src/Orders.java"), "find", List.of());
    }

    private static SemanticDtos.ConceptFollowUpIdentity conceptIdentity() {
        return new SemanticDtos.ConceptFollowUpIdentity("TYPE", Optional.of(target().sourceType()), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static SemanticDtos.EvidenceSourceFollowUpIdentity evidenceIdentity() {
        SemanticDtos.MapperStatementKeyPayload key = new SemanticDtos.MapperStatementKeyPayload("orders", "find");
        SemanticDtos.MapperStatementIdentityPayload statement = new SemanticDtos.MapperStatementIdentityPayload(key,
                "src/OrdersMapper.xml", Optional.empty(), 0, "MAPPER_XML_ELEMENT");
        return new SemanticDtos.EvidenceSourceFollowUpIdentity("MAPPER_STATEMENT", Optional.of(statement), Optional.empty());
    }

    private static SemanticDtos.SourceRangePayload range() {
        return new SemanticDtos.SourceRangePayload("src/Orders.java", new SemanticDtos.TextRangePayload(
                new SemanticDtos.Position(0, 0), new SemanticDtos.Position(0, 1)));
    }

    private static CapabilityExecutionContext context(String capabilityName) {
        return new CapabilityExecutionContext(
                new CapabilityPolicy(capabilityName, "v1", Set.of(CandidateKind.REPOSITORY), 0, 1),
                List.of(), "question", RevisionVector.empty());
    }
}
