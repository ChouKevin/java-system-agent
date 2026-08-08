package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.codeintelligence.planning.EntryPointType;
import com.java.system.agent.codeintelligence.planning.IncomingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.ListEntryPointsExecutionInput;
import com.java.system.agent.codeintelligence.planning.LookupApiRouteExecutionInput;
import com.java.system.agent.codeintelligence.planning.OutgoingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.SuggestApiRouteExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverConceptsExecutionInput;
import com.java.system.agent.codeintelligence.planning.ResolveConceptExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverEventListenersExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverMethodImplementationsExecutionInput;
import com.java.system.agent.codeintelligence.planning.DiscoverTypeMembersExecutionInput;
import com.java.system.agent.codeintelligence.planning.FindInternalReferencesExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetEvidenceSourceExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetMethodSourceExecutionInput;
import com.java.system.agent.codeintelligence.planning.GetSourceSegmentExecutionInput;
import com.java.system.agent.codeintelligence.planning.ResolveSourceSymbolExecutionInput;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailureCode;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import jakarta.validation.Validation;

import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.net.ConnectException;
import java.util.function.Function;
import java.util.stream.Stream;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

/**
 * Java Semantic Service read-only HTTP adapter 的可觀察合約測試
 */
class JavaSemanticServiceHttpAdapterTest {

    @ParameterizedTest
    @MethodSource("discoveryFailureCases")
    void mapsEveryDiscoveryEndpointThroughTheSharedFailurePolicy(DiscoveryEndpoint endpoint, DiscoveryFailure failure) {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test" + endpoint.path()))
                .andRespond(failure.response());
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        if (failure.isContractFailure()) {
            assertThatThrownBy(() -> endpoint.invoke().apply(adapter))
                    .isInstanceOf(CapabilityExecutionContractException.class)
                    .hasMessageNotContaining("secret source body");
        } else {
            CapabilityExecutionResult.Failed result = (CapabilityExecutionResult.Failed) endpoint.invoke().apply(adapter);
            assertThat(result.failure().code()).isEqualTo(failure.code());
            assertThat(result.failure().description()).doesNotContain("secret source body");
        }
        client.server().verify();
    }

    @Test
    void postsTypedConceptDiscoveryWithThePinnedRepositoryScope() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/concepts"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","terms":[{"value":"order","matchMode":"TOKEN_EXACT"}],"kinds":["TYPE"],"operator":"ALL","offset":0,"limit":50}
                        """))
                .andRespond(withSuccess("""
                        {"repoId":"orders","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","normalizedTerms":[],"searchedKinds":["TYPE"],"supportedKinds":["TYPE"],"limitations":[],"candidates":[],"page":{"offset":0,"limit":50,"returnedCount":0,"totalCount":0,"hasMore":false},"coverage":{"status":"COMPLETE","scannedFileCount":0,"extractedFileCount":0,"syntaxFailedFileCount":0},"issueSummaries":[],"availableFollowUps":[],"unavailableFollowUps":[]}
                        """, MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        CapabilityExecutionResult result = adapter.discoverConcepts(repositoryContext("codebase_discover_concepts"),
                new DiscoverConceptsExecutionInput(List.of(new DiscoverConceptsExecutionInput.Term("order", "TOKEN_EXACT")),
                        List.of("TYPE"), Optional.empty(), 0, 50));

        assertThat(result).isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        client.server().verify();
    }

    @Test
    void postsEveryOtherDiscoveryOperationToItsFixedPathAndProjectsTypedSuccess() {
        TestClient client = testClient();
        SemanticDtos.MethodTargetPayload target = targetPayload();
        SemanticDtos.ConceptFollowUpIdentity concept = conceptIdentity();
        SemanticDtos.EvidenceSourceFollowUpIdentity evidence = evidenceIdentity();
        SemanticDtos.SourceRangePayload range = sourceRange();
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/concepts/resolve")).andExpect(method(POST))
                .andExpect(content().json("{\"repoId\":\"orders\",\"expectedRevision\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"identity\":{\"kind\":\"TYPE\",\"sourceType\":{\"javaType\":{\"packageName\":\"com.example\",\"className\":\"Orders\"},\"sourceFile\":\"src/Orders.java\"}}}"))
                .andRespond(withSuccess(resolveConceptSuccess(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/event-listeners")).andExpect(method(POST))
                .andExpect(content().json("{\"repoId\":\"orders\",\"expectedRevision\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"eventType\":\"com.example.Event\",\"offset\":0,\"limit\":1}"))
                .andRespond(withSuccess(listenersSuccess(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/method-implementations")).andExpect(method(POST))
                .andExpect(content().json("""
                        {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","declarationTarget":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/OrderService.java"},"methodName":"find","parameterTypes":["java.lang.String"]}}
                        """))
                .andRespond(withSuccess(implementationsSuccess(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/type-members")).andExpect(method(POST))
                .andExpect(content().json("""
                        {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},"memberKinds":["METHOD"],"offset":0,"limit":1}
                        """))
                .andRespond(withSuccess(membersSuccess(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/internal-references")).andExpect(method(POST))
                .andExpect(content().json("""
                        {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","target":{"kind":"METHOD","identity":{"sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},"methodName":"find","parameterTypes":[]}},"offset":0,"limit":1}
                        """))
                .andRespond(withSuccess(referencesSuccess(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/evidence-source")).andExpect(method(POST))
                .andExpect(content().json("""
                        {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","identity":{"kind":"MAPPER_STATEMENT","statementIdentity":{"statementKey":{"namespace":"orders","statementId":"find"},"resourcePath":"src/OrdersMapper.xml","documentOrdinal":0,"representation":"MAPPER_XML_ELEMENT"}}}
                        """))
                .andRespond(withSuccess(evidenceSuccess(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/method-source")).andExpect(method(POST))
                .andExpect(content().json("""
                        {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/OrderService.java"},"methodName":"find","parameterTypes":["java.lang.String"]}}
                        """))
                .andRespond(withSuccess(methodSourceSuccess(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/source-segment")).andExpect(method(POST))
                .andExpect(content().json("""
                        {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","location":{"sourceFile":"src/Orders.java","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}},"contextLines":0}
                        """))
                .andRespond(withSuccess(segmentSuccess(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/source-symbols/resolve")).andExpect(method(POST))
                .andExpect(content().json("""
                        {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","context":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/OrderService.java","method":{"name":"find","parameterTypes":["java.lang.String"]}},"symbol":"order"}
                        """))
                .andRespond(withSuccess(symbolSuccess(), MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertSucceeded(() -> adapter.resolveConcept(followUpContext("codebase_resolve_concept", new ResolveConceptExecutionInput(concept)), new ResolveConceptExecutionInput(concept)));
        assertSucceeded(() -> adapter.discoverEventListeners(repositoryContext("codebase_discover_event_listeners"), new DiscoverEventListenersExecutionInput("com.example.Event", 0, 1)));
        assertSucceeded(() -> adapter.discoverMethodImplementations(targetContext("codebase_discover_method_implementations"), new DiscoverMethodImplementationsExecutionInput(Optional.empty())));
        DiscoverTypeMembersExecutionInput members = new DiscoverTypeMembersExecutionInput(target.sourceType(), List.of("METHOD"), Optional.empty(), 0, 1);
        assertSucceeded(() -> adapter.discoverTypeMembers(followUpContext("codebase_discover_type_members", members), members));
        FindInternalReferencesExecutionInput references = new FindInternalReferencesExecutionInput(new SemanticDtos.InternalReferenceFollowUpTarget("METHOD", target), 0, 1);
        assertSucceeded(() -> adapter.findInternalReferences(followUpContext("codebase_find_internal_references", references), references));
        GetEvidenceSourceExecutionInput evidenceInput = new GetEvidenceSourceExecutionInput(evidence);
        assertSucceeded(() -> adapter.getEvidenceSource(followUpContext("codebase_get_evidence_source", evidenceInput), evidenceInput));
        assertSucceeded(() -> adapter.getMethodSource(targetContext("codebase_get_method_source"), new GetMethodSourceExecutionInput(Optional.empty())));
        GetSourceSegmentExecutionInput segment = new GetSourceSegmentExecutionInput(range, 0);
        assertSucceeded(() -> adapter.getSourceSegment(followUpContext("codebase_get_source_segment", segment), segment));
        ResolveSourceSymbolExecutionInput symbol = new ResolveSourceSymbolExecutionInput("order", Optional.empty(), Optional.empty());
        assertSucceeded(() -> adapter.resolveSourceSymbol(targetContext("codebase_resolve_source_symbol"), symbol));
        client.server().verify();
    }

    @Test
    void executesBothCallGraphsForAFollowUpCandidateWithItsBoundTargetAndPinnedScope() {
        TestClient client = testClient();
        SemanticDtos.MethodTargetPayload target = graphTargetPayload();
        OutgoingCallGraphExecutionInput outgoing = new OutgoingCallGraphExecutionInput(1, Optional.of(target));
        IncomingCallGraphExecutionInput incoming = new IncomingCallGraphExecutionInput(1, Optional.of(target));
        client.server().expect(once(), requestTo("https://semantic.test/v1/analyses/call-graphs/outgoing"))
                .andExpect(method(POST))
                .andExpect(content().json(graphRequest(target)))
                .andRespond(withSuccess(graphResponse(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/analyses/call-graphs/incoming"))
                .andExpect(method(POST))
                .andExpect(content().json(graphRequest(target)))
                .andRespond(withSuccess(graphResponse(), MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertSucceeded(() -> adapter.outgoingCallGraph(
                followUpContext("codebase_outgoing_call_graph", outgoing), outgoing));
        assertSucceeded(() -> adapter.incomingCallGraph(
                followUpContext("codebase_incoming_call_graph", incoming), incoming));

        client.server().verify();
    }

    @Test
    void rejectsBoundTargetsInjectedIntoDirectCallGraphInputsBeforeHttp() {
        TestClient client = testClient();
        SemanticDtos.MethodTargetPayload target = graphTargetPayload();
        client.server().expect(org.springframework.test.web.client.ExpectedCount.never(),
                requestTo("https://semantic.test/v1/analyses/call-graphs/outgoing"));
        client.server().expect(org.springframework.test.web.client.ExpectedCount.never(),
                requestTo("https://semantic.test/v1/analyses/call-graphs/incoming"));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertThatThrownBy(() -> adapter.outgoingCallGraph(targetContext("codebase_outgoing_call_graph"),
                new OutgoingCallGraphExecutionInput(1, Optional.of(target))))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> adapter.incomingCallGraph(targetContext("codebase_incoming_call_graph"),
                new IncomingCallGraphExecutionInput(1, Optional.of(target))))
                .isInstanceOf(CapabilityExecutionContractException.class);

        client.server().verify();
    }

    @Test
    void rejectsCallGraphResponsesThatDoNotMatchThePinnedRevision() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/analyses/call-graphs/outgoing"))
                .andRespond(withSuccess(graphResponse().replace(
                        "\"analyzedRevision\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"",
                        "\"analyzedRevision\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\""),
                        MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertThatThrownBy(() -> adapter.outgoingCallGraph(targetContext("codebase_outgoing_call_graph"),
                new OutgoingCallGraphExecutionInput(1)))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageContaining("revision");

        client.server().verify();
    }

    @Test
    void rejectsDiscoveryFollowUpsWithWrongCapabilityOrCanonicalPayloadBeforeHttp() {
        TestClient client = testClient();
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());
        ResolveConceptExecutionInput input = new ResolveConceptExecutionInput(conceptIdentity());
        CapabilityExecutionContext wrongCapability = followUpContext("codebase_get_evidence_source", input);
        CapabilityExecutionContext wrongPayload = followUpContext("codebase_resolve_concept",
                new ResolveConceptExecutionInput(new SemanticDtos.ConceptFollowUpIdentity("TYPE",
                        Optional.of(new SemanticDtos.SourceTypeIdentityPayload(
                                new SemanticDtos.JavaTypeIdentityPayload("com.example", "Other"), "src/Other.java")),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));

        assertThatThrownBy(() -> adapter.resolveConcept(wrongCapability, input))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> adapter.resolveConcept(wrongPayload, input))
                .isInstanceOf(CapabilityExecutionContractException.class);
        client.server().verify();
    }

    @Test
    void rejectsDiscoveryResponseScopeMismatchWithoutExposingSourceContent() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/concepts"))
                .andRespond(withSuccess(conceptsScopeMismatchSuccess(), MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());
        DiscoverConceptsExecutionInput input = new DiscoverConceptsExecutionInput(
                List.of(new DiscoverConceptsExecutionInput.Term("order", "TOKEN_EXACT")), List.of("TYPE"),
                Optional.empty(), 0, 50);

        assertThatThrownBy(() -> adapter.discoverConcepts(repositoryContext("codebase_discover_concepts"), input))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageNotContaining("secret source body");
        client.server().verify();
    }

    @Test
    void rejectsDiscoveryResponseRevisionMismatchWithoutExposingSourceContent() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/source-segment"))
                .andRespond(withSuccess(segmentRevisionMismatchSuccess(), MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());
        GetSourceSegmentExecutionInput input = new GetSourceSegmentExecutionInput(sourceRange(), 0);

        assertThatThrownBy(() -> adapter.getSourceSegment(
                followUpContext("codebase_get_source_segment", input), input))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageNotContaining("secret source body");
        client.server().verify();
    }

    @Test
    void rejectsUnsupportedDiscoveryIssueCodeWithoutExposingSourceContent() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/source-symbols/resolve"))
                .andRespond(withSuccess(symbolSuccessWithUnsupportedIssue(), MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertThatThrownBy(() -> adapter.resolveSourceSymbol(targetContext("codebase_resolve_source_symbol"),
                new ResolveSourceSymbolExecutionInput("order", Optional.empty(), Optional.empty())))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageContaining("unsupported source symbol issue code")
                .hasMessageNotContaining("secret source body");
        client.server().verify();
    }

    @Test
    void rejectsMissingOrMalformedDiscoveryFollowUpPayloadBeforeHttp() {
        TestClient client = testClient();
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());
        ResolveConceptExecutionInput input = new ResolveConceptExecutionInput(conceptIdentity());

        assertThatThrownBy(() -> adapter.resolveConcept(
                followUpContextWithPayload("codebase_resolve_concept", "{}"), input))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageNotContaining("secret source body");
        assertThatThrownBy(() -> adapter.resolveConcept(
                followUpContextWithPayload("codebase_resolve_concept", "{"), input))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageNotContaining("secret source body");
        client.server().verify();
    }

    @Test
    void rejectsMethodImplementationResponseForAnotherRequestedTarget() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/method-implementations"))
                .andRespond(withSuccess(implementationsTargetMismatchSuccess(), MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertThatThrownBy(() -> adapter.discoverMethodImplementations(
                targetContext("codebase_discover_method_implementations"),
                new DiscoverMethodImplementationsExecutionInput(Optional.empty())))
                .isInstanceOf(CapabilityExecutionContractException.class);
        client.server().verify();
    }

    @Test
    void rejectsEvidenceAndSourceSegmentResponsesForAnotherBoundIdentityOrNonContainingLocation() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/evidence-source"))
                .andRespond(withSuccess(evidenceIdentityMismatchSuccess(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/source-segment"))
                .andRespond(withSuccess(segmentNonContainingLocationSuccess(), MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());
        GetEvidenceSourceExecutionInput evidence = new GetEvidenceSourceExecutionInput(evidenceIdentity());
        GetSourceSegmentExecutionInput segment = new GetSourceSegmentExecutionInput(sourceRange(), 0);

        assertThatThrownBy(() -> adapter.getEvidenceSource(
                followUpContext("codebase_get_evidence_source", evidence), evidence))
                .isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> adapter.getSourceSegment(
                followUpContext("codebase_get_source_segment", segment), segment))
                .isInstanceOf(CapabilityExecutionContractException.class);
        client.server().verify();
    }

    @Test
    void acceptsSourceSegmentResponseWithExpandedContextThatContainsTheRequestedRange() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/source-segment"))
                .andRespond(withSuccess(segmentExpandedContextSuccess(), MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());
        GetSourceSegmentExecutionInput input = new GetSourceSegmentExecutionInput(sourceRange(), 0);

        assertThat(adapter.getSourceSegment(followUpContext("codebase_get_source_segment", input), input))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        client.server().verify();
    }

    @Test
    void rejectsProviderFollowUpWithoutRequiredIdentityWithoutExposingSourceContent() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/discovery/evidence-source"))
                .andRespond(withSuccess(evidenceSuccessWithMissingFollowUpIdentity(), MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());
        GetEvidenceSourceExecutionInput input = new GetEvidenceSourceExecutionInput(evidenceIdentity());

        assertThatThrownBy(() -> adapter.getEvidenceSource(
                followUpContext("codebase_get_evidence_source", input), input))
                .isInstanceOf(CapabilityExecutionContractException.class)
                .hasMessageNotContaining("secret source body");
        client.server().verify();
    }

    @Test
    void sendsTheConfiguredTokenAndMapsRepositoryCatalogAndRevision() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andExpect(method(GET))
                .andExpect(header("X-Api-Token", "test-token"))
                .andRespond(withSuccess("""
                        [{"repoId":"orders","mode":"REMOTE","displayName":"Orders","currentBranch":"main","currentRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","cloned":true}]
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/orders"))
                .andExpect(method(GET))
                .andExpect(header("X-Api-Token", "test-token"))
                .andRespond(withSuccess("""
                        {"repoId":"orders","mode":"REMOTE","displayName":"Orders","currentBranch":"main","currentRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","cloned":true}
                        """, MediaType.APPLICATION_JSON));

        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertThat(adapter.availableRepositories()).extracting(descriptor -> descriptor.repositoryId().value())
                .containsExactly("orders");
        assertThat(adapter.currentRevision(new RepositoryId("orders")))
                .isEqualTo(new RepositoryRevisionResult.Ready(new RepositoryRevision(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
        client.server().verify();
    }

    @Test
    void mapsEveryCapabilityOperationToOneReadOnlyHttpRequest() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/orders/entry-points?expectedRevision=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&types=API"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"repoId":"orders","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","entryPoints":[{"className":"OrderController","packageName":"com.example.web","packagePath":"com/example/web","description":"Order entry points","basePaths":["/orders"],"methods":[{"type":"API","name":"list","description":"List orders","apiUrl":"/orders","httpMethods":["GET"],"swaggerDescriptions":["Lists orders"],"analysisTarget":{"status":"UNRESOLVED","target":null,"candidates":[],"reasonCode":"TARGET_NOT_FOUND"}}]}]}
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/api-routes/lookup"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"apiPath":"/orders","httpMethod":null,"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                        """))
                .andRespond(withSuccess("{" + "\"candidates\":[],\"observations\":[]}" , MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/api-routes/suggest"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"apiPath":"/orders","httpMethod":null,"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","limit":3}
                        """))
                .andRespond(withSuccess("{" + "\"candidates\":[],\"observations\":[]}" , MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/analyses/call-graphs/outgoing"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","depth":1,"target":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/OrderService.java"},"methodName":"find","parameterTypes":["java.lang.String"]}}
                        """))
                .andRespond(withSuccess(graphResponse(), MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/analyses/call-graphs/incoming"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","depth":1,"target":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/OrderService.java"},"methodName":"find","parameterTypes":["java.lang.String"]}}
                        """))
                .andRespond(withSuccess(graphResponse(), MediaType.APPLICATION_JSON));

        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertThat(adapter.listEntryPoints(repositoryContext("codebase_list_entry_points"), new ListEntryPointsExecutionInput(EntryPointType.API)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.lookupApiRoute(repositoryContext("codebase_lookup_api_route"), new LookupApiRouteExecutionInput("/orders", null)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.suggestApiRoute(repositoryContext("codebase_suggest_api_route"), new SuggestApiRouteExecutionInput("/orders", null, 3)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.outgoingCallGraph(targetContext("codebase_outgoing_call_graph"), new OutgoingCallGraphExecutionInput(1)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.incomingCallGraph(targetContext("codebase_incoming_call_graph"), new IncomingCallGraphExecutionInput(1)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        client.server().verify();
    }

    @Test
    void mapsExpectedExternalAndTransportFailuresWithoutRetryAndRejectsMalformedDocuments() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/api-routes/lookup"))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON).body("""
                        {"errorCode":"SEMANTIC_REQUEST_TIMEOUT","message":"service timeout","repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","currentRevision":null,"target":null,"candidates":[],"requestId":"request-1"}
                        """));
        client.server().expect(once(), requestTo("https://semantic.test/v1/api-routes/suggest"))
                .andRespond(withException(new SocketTimeoutException("timeout")));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/orders/entry-points?expectedRevision=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .andRespond(withSuccess("{", MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        CapabilityExecutionResult.Failed external = (CapabilityExecutionResult.Failed) adapter.lookupApiRoute(
                repositoryContext("codebase_lookup_api_route"), new LookupApiRouteExecutionInput("/orders", null));
        CapabilityExecutionResult.Failed transport = (CapabilityExecutionResult.Failed) adapter.suggestApiRoute(
                repositoryContext("codebase_suggest_api_route"), new SuggestApiRouteExecutionInput("/orders", null, 3));

        assertThat(external.failure().code()).isEqualTo(CapabilityExecutionFailureCode.TIMEOUT);
        assertThat(transport.failure().code()).isEqualTo(CapabilityExecutionFailureCode.TIMEOUT);
        assertThatThrownBy(() -> adapter.listEntryPoints(repositoryContext("codebase_list_entry_points"), new ListEntryPointsExecutionInput(null)))
                .isInstanceOf(CapabilityExecutionContractException.class);
        client.server().verify();
    }

    @Test
    void rejectsRepositoryQueriesWithoutAnExpectedRevisionBeforeHttp() {
        TestClient client = testClient();
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());
        CapabilityExecutionContext context = repositoryContextWithoutExpectedRevision(
                "codebase_list_entry_points");

        assertThatThrownBy(() -> adapter.listEntryPoints(context, new ListEntryPointsExecutionInput(EntryPointType.API)))
                .isInstanceOf(CapabilityExecutionContractException.class);

        client.server().verify();
    }

    @Test
    void mapsRevisionlessRepositoryToNotReadyAndAllowsOpenApiValidUnclonedRevision() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/orders"))
                .andRespond(withSuccess("""
                        {"repoId":"orders","mode":"REMOTE","displayName":"Orders","currentBranch":null,"currentRevision":null,"cloned":true}
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories/fixture"))
                .andRespond(withSuccess("""
                        {"repoId":"fixture","mode":"LOCAL_FIXTURE","displayName":"Fixture","currentBranch":null,"currentRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","cloned":false}
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andRespond(withSuccess("""
                        [{"repoId":"orders","mode":"BROKEN","displayName":"Orders","currentBranch":null,"currentRevision":null,"cloned":false}]
                        """, MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertThat(adapter.currentRevision(new RepositoryId("orders"))).isEqualTo(new RepositoryRevisionResult.Failed(
                new com.java.system.agent.answering.port.out.RepositoryRevisionFailure(
                        RepositoryRevisionFailureCode.DEPENDENCY_NOT_READY,
                        "Java Semantic Service repository is not ready", "java-semantic-service:GET /v1/repositories/{repoId}")));
        assertThat(adapter.currentRevision(new RepositoryId("fixture"))).isEqualTo(new RepositoryRevisionResult.Ready(
                new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
        assertThatThrownBy(adapter::availableRepositories)
                .isInstanceOf(CapabilityExecutionContractException.class);
        client.server().verify();
    }

    @Test
    void rejectsAResponseGraphWithoutExactlyOneRootTarget() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/analyses/call-graphs/outgoing"))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","rootNodeId":"missing","traversal":{"requestedDepth":1,"expandedNodeCount":0,"nodeBudget":0,"rootDirectCallsComplete":true,"limitReason":"NONE"},"nodes":[],"edges":[],"warnings":[],"errors":[]}
                        """, MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertThatThrownBy(() -> adapter.outgoingCallGraph(targetContext("codebase_outgoing_call_graph"), new OutgoingCallGraphExecutionInput(1)))
                .isInstanceOf(CapabilityExecutionContractException.class);
        client.server().verify();
    }

    @Test
    void rejectsMissingRequiredJsonPrimitiveFieldsInsteadOfAcceptingDefaultValues() {
        TestClient client = testClient();
        client.server().expect(once(), requestTo("https://semantic.test/v1/repositories"))
                .andRespond(withSuccess("""
                        [{"repoId":"orders","mode":"REMOTE","displayName":"Orders","currentBranch":null,"currentRevision":null}]
                        """, MediaType.APPLICATION_JSON));
        client.server().expect(once(), requestTo("https://semantic.test/v1/analyses/call-graphs/outgoing"))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","rootNodeId":"root","traversal":{"requestedDepth":1,"expandedNodeCount":0,"nodeBudget":0,"limitReason":"NONE"},"nodes":[{"nodeId":"root","target":{"sourceFile":"src/OrderService.java","packageName":"com.example","className":"OrderService","methodName":"find","parameterTypes":[]},"externalSymbol":null,"contentState":"FULL_SOURCE","traversalState":"EXPANDED","dispatchKind":"SYNCHRONOUS","declarationRange":null}],"edges":[],"warnings":[],"errors":[]}
                        """, MediaType.APPLICATION_JSON));
        JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(client.restClient());

        assertThatThrownBy(adapter::availableRepositories).isInstanceOf(CapabilityExecutionContractException.class);
        assertThatThrownBy(() -> adapter.outgoingCallGraph(targetContext("codebase_outgoing_call_graph"), new OutgoingCallGraphExecutionInput(1)))
                .isInstanceOf(CapabilityExecutionContractException.class);
        client.server().verify();
    }

    private static String graphResponse() {
        return """
                {"status":"SUCCESS","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","rootNodeId":"root","traversal":{"requestedDepth":1,"expandedNodeCount":0,"nodeBudget":0,"rootDirectCallsComplete":true,"limitReason":"NONE"},"nodes":[{"nodeId":"root","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/OrderService.java"},"methodName":"find","parameterTypes":["java.lang.String"]},"externalSymbol":null,"contentState":"FULL_SOURCE","traversalState":"EXPANDED","dispatchKind":"SYNCHRONOUS","declarationRange":{"start":{"line":3,"character":1},"end":{"line":3,"character":5}},"availableFollowUps":[]}],"edges":[{"callerNodeId":"root","calleeNodeId":"child","callSite":{"sourceFile":"src/OrderService.java","range":{"start":{"line":4,"character":2},"end":{"line":4,"character":8}}},"callExpression":"load()","resolutionStrategy":"JDT_CALL_HIERARCHY","category":"RESOLVED_ANALYZABLE","evidence":[],"availableFollowUps":[]}],"warnings":[],"errors":[]}
                """;
    }

    private static String graphRequest(SemanticDtos.MethodTargetPayload target) {
        return """
                {"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","depth":1,"target":{"sourceType":{"javaType":{"packageName":"%s","className":"%s"},"sourceFile":"%s"},"methodName":"%s","parameterTypes":["java.lang.String"]}}
                """.formatted(target.sourceType().javaType().packageName(), target.sourceType().javaType().className(),
                target.sourceType().sourceFile(), target.methodName());
    }

    private static CapabilityExecutionContext repositoryContext(String name) {
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        IssuedCandidate candidate = new IssuedCandidate(
                new CandidateHandle("candidate-1", new HandleBinding(new AnalysisRunId("run-1"),
                        new AnalysisAttemptId("attempt-1"), revisions), CandidateKind.REPOSITORY),
                new RepositoryCandidate(repositoryId, "Orders"));
        return new CapabilityExecutionContext(descriptor(name, CandidateKind.REPOSITORY), List.of(candidate),
                "Find orders", revisions);
    }

    private static Stream<Arguments> discoveryFailureCases() {
        return discoveryEndpoints().flatMap(endpoint -> Stream.of(DiscoveryFailure.values())
                .map(failure -> Arguments.of(endpoint, failure)));
    }

    private static Stream<DiscoveryEndpoint> discoveryEndpoints() {
        SemanticDtos.MethodTargetPayload target = targetPayload();
        SemanticDtos.ConceptFollowUpIdentity concept = conceptIdentity();
        SemanticDtos.EvidenceSourceFollowUpIdentity evidence = evidenceIdentity();
        SemanticDtos.SourceRangePayload range = sourceRange();
        ResolveConceptExecutionInput resolve = new ResolveConceptExecutionInput(concept);
        DiscoverConceptsExecutionInput concepts = new DiscoverConceptsExecutionInput(
                List.of(new DiscoverConceptsExecutionInput.Term("order", "TOKEN_EXACT")), List.of("TYPE"),
                Optional.empty(), 0, 50);
        DiscoverEventListenersExecutionInput listeners = new DiscoverEventListenersExecutionInput("com.example.Event", 0,
                1);
        DiscoverMethodImplementationsExecutionInput implementations = new DiscoverMethodImplementationsExecutionInput(
                Optional.empty());
        DiscoverTypeMembersExecutionInput members = new DiscoverTypeMembersExecutionInput(target.sourceType(),
                List.of("METHOD"), Optional.empty(), 0, 1);
        FindInternalReferencesExecutionInput references = new FindInternalReferencesExecutionInput(
                new SemanticDtos.InternalReferenceFollowUpTarget("METHOD", target), 0, 1);
        GetEvidenceSourceExecutionInput evidenceInput = new GetEvidenceSourceExecutionInput(evidence);
        GetSourceSegmentExecutionInput segment = new GetSourceSegmentExecutionInput(range, 0);
        GetMethodSourceExecutionInput methodSource = new GetMethodSourceExecutionInput(Optional.empty());
        ResolveSourceSymbolExecutionInput sourceSymbol = new ResolveSourceSymbolExecutionInput("order",
                Optional.empty(), Optional.empty());
        return Stream.of(
                endpoint("/v1/discovery/concepts", adapter -> adapter.discoverConcepts(
                        repositoryContext("codebase_discover_concepts"), concepts)),
                endpoint("/v1/discovery/concepts/resolve", adapter -> adapter.resolveConcept(
                        followUpContext("codebase_resolve_concept", resolve), resolve)),
                endpoint("/v1/discovery/event-listeners", adapter -> adapter.discoverEventListeners(
                        repositoryContext("codebase_discover_event_listeners"), listeners)),
                endpoint("/v1/discovery/method-implementations", adapter -> adapter.discoverMethodImplementations(
                        targetContext("codebase_discover_method_implementations"), implementations)),
                endpoint("/v1/discovery/type-members", adapter -> adapter.discoverTypeMembers(
                        followUpContext("codebase_discover_type_members", members), members)),
                endpoint("/v1/discovery/internal-references", adapter -> adapter.findInternalReferences(
                        followUpContext("codebase_find_internal_references", references), references)),
                endpoint("/v1/discovery/evidence-source", adapter -> adapter.getEvidenceSource(
                        followUpContext("codebase_get_evidence_source", evidenceInput), evidenceInput)),
                endpoint("/v1/discovery/method-source", adapter -> adapter.getMethodSource(
                        targetContext("codebase_get_method_source"), methodSource)),
                endpoint("/v1/discovery/source-segment", adapter -> adapter.getSourceSegment(
                        followUpContext("codebase_get_source_segment", segment), segment)),
                endpoint("/v1/discovery/source-symbols/resolve", adapter -> adapter.resolveSourceSymbol(
                        targetContext("codebase_resolve_source_symbol"), sourceSymbol)));
    }

    private static DiscoveryEndpoint endpoint(String path,
                                              Function<JavaSemanticServiceHttpAdapter, CapabilityExecutionResult> invoke) {
        return new DiscoveryEndpoint(path, invoke);
    }

    private enum DiscoveryFailure {
        UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "SEMANTIC_UNAUTHORIZED", CapabilityExecutionFailureCode.FORBIDDEN),
        REPOSITORY_NOT_FOUND(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", CapabilityExecutionFailureCode.REPOSITORY_NOT_FOUND),
        REVISION_MISMATCH(HttpStatus.CONFLICT, "REPOSITORY_REVISION_MISMATCH", CapabilityExecutionFailureCode.REVISION_CONFLICT),
        INVALID_INPUT(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", CapabilityExecutionFailureCode.DEPENDENCY_FAILURE),
        TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "SEMANTIC_REQUEST_TIMEOUT", CapabilityExecutionFailureCode.TIMEOUT),
        UNAVAILABLE(null, null, CapabilityExecutionFailureCode.DEPENDENCY_UNAVAILABLE),
        MALFORMED_SUCCESS(null, null, null);

        private final HttpStatus status;
        private final String errorCode;
        private final CapabilityExecutionFailureCode code;

        DiscoveryFailure(HttpStatus status, String errorCode, CapabilityExecutionFailureCode code) {
            this.status = status;
            this.errorCode = errorCode;
            this.code = code;
        }

        ResponseCreator response() {
            if (this == MALFORMED_SUCCESS) {
                return withSuccess("{\"source\":\"secret source body\"", MediaType.APPLICATION_JSON);
            }
            if (this == UNAVAILABLE) {
                return withException(new ConnectException("unavailable"));
            }
            return withStatus(status).contentType(MediaType.APPLICATION_JSON).body(errorResponseBody());
        }

        private String errorResponseBody() {
            return "{\"errorCode\":\"" + errorCode + "\",\"message\":\"safe\",\"repoId\":\"orders\","
                    + "\"expectedRevision\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                    + "\"currentRevision\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"target\":null,"
                    + "\"candidates\":[],\"requestId\":\"request-1\"}";
        }

        boolean isContractFailure() {
            return this == MALFORMED_SUCCESS;
        }

        CapabilityExecutionFailureCode code() {
            return code;
        }
    }

    private record DiscoveryEndpoint(String path,
                                     Function<JavaSemanticServiceHttpAdapter, CapabilityExecutionResult> invoke) {
    }

    private static CapabilityExecutionContext followUpContext(String name, Object input) {
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        CanonicalCapabilityPayloadCodec codec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        return followUpContext(name, revisions, repositoryId, revision, codec.encode(input));
    }

    private static CapabilityExecutionContext followUpContextWithPayload(String name, String payload) {
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        return followUpContext(name, revisions, repositoryId, revision, new CapabilityInputPayload(payload));
    }

    private static CapabilityExecutionContext followUpContext(String name, RevisionVector revisions,
                                                               RepositoryId repositoryId, RepositoryRevision revision,
                                                               CapabilityInputPayload payload) {
        FollowUpCandidate followUp = new FollowUpCandidate(repositoryId, revision, name, "v1", payload,
                "Semantic follow-up");
        IssuedCandidate candidate = new IssuedCandidate(new CandidateHandle("candidate-follow-up",
                new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), revisions),
                CandidateKind.FOLLOW_UP), followUp);
        return new CapabilityExecutionContext(descriptor(name, CandidateKind.FOLLOW_UP), List.of(candidate),
                "Find orders", revisions);
    }

    private static void assertSucceeded(java.util.function.Supplier<CapabilityExecutionResult> request) {
        assertThat(request.get()).isInstanceOf(CapabilityExecutionResult.Succeeded.class);
    }

    private static String resolveConceptSuccess() {
        return """
                {"repoId":"orders","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","candidate":{"identity":{"kind":"TYPE","sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"}},"displayValue":"Orders","matchedTerms":["order"],"authority":"SYNTAX_RESOLVED","evidence":[],"availableFollowUps":[]}}
                """;
    }

    private static String conceptsScopeMismatchSuccess() {
        return """
                {"repoId":"other","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","normalizedTerms":[],"searchedKinds":["TYPE"],"supportedKinds":["TYPE"],"limitations":[],"candidates":[],"page":{"offset":0,"limit":50,"returnedCount":0,"totalCount":0,"hasMore":false},"coverage":{"status":"COMPLETE","scannedFileCount":0,"extractedFileCount":0,"syntaxFailedFileCount":0},"issueSummaries":[],"availableFollowUps":[],"unavailableFollowUps":[]}
                """;
    }

    private static String segmentRevisionMismatchSuccess() {
        return segmentSuccess().replace("\"analyzedRevision\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"",
                        "\"analyzedRevision\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"")
                .replace("\"content\":\"x\"", "\"content\":\"secret source body\"");
    }

    private static String listenersSuccess() {
        return """
                {"repoId":"orders","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","requestedEventType":"com.example.Event","candidates":[],"page":{"offset":0,"limit":1,"returnedCount":0,"totalCount":0,"hasMore":false},"observationSummaries":[],"availableFollowUps":[]}
                """;
    }

    private static String implementationsSuccess() {
        return """
                {"repoId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","requestedTarget":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/OrderService.java"},"methodName":"find","parameterTypes":["java.lang.String"]},"candidates":[],"limits":{"limit":1,"returnedCount":0,"totalCount":0,"truncated":false},"resolution":{"status":"COMPLETE","issueSummaries":[]}}
                """;
    }

    private static String implementationsTargetMismatchSuccess() {
        return implementationsSuccess().replace("\"methodName\":\"find\"", "\"methodName\":\"other\"");
    }

    private static String evidenceIdentityMismatchSuccess() {
        return evidenceSuccess().replace("\"statementId\":\"find\"", "\"statementId\":\"other\"");
    }

    private static String evidenceSuccessWithMissingFollowUpIdentity() {
        return evidenceSuccess().replace("\"content\":\"x\"", "\"content\":\"secret source body\"")
                .replace("\"availableFollowUps\":[]", """
                        "availableFollowUps":[{"operation":"GET_EVIDENCE_SOURCE","api":{"method":"POST","path":"/v1/discovery/evidence-source","operationId":"getEvidenceSource"},"request":{"repoId":"orders","expectedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]
                        """);
    }

    private static String membersSuccess() {
        return """
                {"repoId":"orders","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},"typeKind":"CLASS","annotations":[],"implementedTypes":[],"extendedTypes":[],"members":[],"page":{"offset":0,"limit":1,"returnedCount":0,"totalCount":0,"hasMore":false},"coverage":{"status":"COMPLETE","scannedFileCount":0,"extractedFileCount":0,"syntaxFailedFileCount":0},"availableFollowUps":[]}
                """;
    }

    private static String referencesSuccess() {
        return """
                {"repoId":"orders","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"COMPLETE","targetDeclaration":{"target":{"kind":"METHOD","identity":{"sourceType":{"javaType":{"packageName":"com.example","className":"Orders"},"sourceFile":"src/Orders.java"},"methodName":"find","parameterTypes":[]}},"declarationRange":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"availableFollowUps":[]},"totalReferenceCount":0,"referenceGroups":[],"page":{"offset":0,"limit":1,"returnedCount":0,"totalCount":0,"hasMore":false},"issueSummaries":[],"availableFollowUps":[]}
                """;
    }

    private static String evidenceSuccess() {
        return """
                {"repoId":"orders","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","identity":{"kind":"MAPPER_STATEMENT","statementIdentity":{"statementKey":{"namespace":"orders","statementId":"find"},"resourcePath":"src/OrdersMapper.xml","documentOrdinal":0,"representation":"MAPPER_XML_ELEMENT"}},"location":{"sourceFile":"src/Orders.java","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}},"segment":{"location":{"sourceFile":"src/Orders.java","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}},"content":"x"},"availableFollowUps":[]}
                """;
    }

    private static String methodSourceSuccess() {
        return evidenceSuccess().replace("\"identity\":{\"kind\":\"MAPPER_STATEMENT\",\"statementIdentity\":{\"statementKey\":{\"namespace\":\"orders\",\"statementId\":\"find\"},\"resourcePath\":\"src/OrdersMapper.xml\",\"documentOrdinal\":0,\"representation\":\"MAPPER_XML_ELEMENT\"}},\"location\":",
                "\"declarationLocation\":");
    }

    private static String segmentSuccess() {
        return "{\"repoId\":\"orders\",\"analyzedRevision\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"segment\":{\"location\":{\"sourceFile\":\"src/Orders.java\",\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":1}}},\"content\":\"x\"},\"contextTruncated\":false,\"availableFollowUps\":[]}";
    }

    private static String segmentNonContainingLocationSuccess() {
        return segmentSuccess().replace("\"sourceFile\":\"src/Orders.java\"", "\"sourceFile\":\"src/Other.java\"");
    }

    private static String segmentExpandedContextSuccess() {
        return segmentSuccess().replace("\"end\":{\"line\":0,\"character\":1}",
                "\"end\":{\"line\":0,\"character\":2}");
    }

    private static String symbolSuccess() {
        return "{\"repoId\":\"orders\",\"analyzedRevision\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"status\":\"RESOLVED\",\"contextCandidates\":[],\"contextCandidateLimits\":{\"limit\":1,\"returnedCount\":0,\"totalCount\":0,\"truncated\":false},\"candidates\":[],\"issues\":[]}";
    }

    private static String symbolSuccessWithUnsupportedIssue() {
        return symbolSuccess().replace("\"issues\":[]",
                "\"issues\":[{\"code\":\"UNSUPPORTED_ISSUE\",\"count\":1}]");
    }

    private static SemanticDtos.MethodTargetPayload targetPayload() {
        return new SemanticDtos.MethodTargetPayload(new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload("com.example", "Orders"), "src/Orders.java"),
                "find", List.of());
    }

    private static SemanticDtos.MethodTargetPayload graphTargetPayload() {
        return new SemanticDtos.MethodTargetPayload(new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload("com.example", "OrderService"), "src/OrderService.java"),
                "find", List.of("java.lang.String"));
    }

    private static SemanticDtos.ConceptFollowUpIdentity conceptIdentity() {
        return new SemanticDtos.ConceptFollowUpIdentity("TYPE", Optional.of(targetPayload().sourceType()), Optional.empty(),
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

    private static SemanticDtos.SourceRangePayload sourceRange() {
        return new SemanticDtos.SourceRangePayload("src/Orders.java", new SemanticDtos.TextRangePayload(
                new SemanticDtos.Position(0, 0), new SemanticDtos.Position(0, 1)));
    }

    private static CapabilityExecutionContext repositoryContextWithoutExpectedRevision(String name) {
        RepositoryId repositoryId = new RepositoryId("orders");
        RevisionVector revisions = RevisionVector.empty();
        IssuedCandidate candidate = new IssuedCandidate(
                new CandidateHandle("candidate-1", new HandleBinding(new AnalysisRunId("run-1"),
                        new AnalysisAttemptId("attempt-1"), revisions), CandidateKind.REPOSITORY),
                new RepositoryCandidate(repositoryId, "Orders"));
        return new CapabilityExecutionContext(descriptor(name, CandidateKind.REPOSITORY), List.of(candidate),
                "Find orders", revisions);
    }

    private static CapabilityExecutionContext targetContext(String name) {
        RepositoryId repositoryId = new RepositoryId("orders");
        RepositoryRevision revision = new RepositoryRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        SemanticTarget target = new JavaSemanticResultMapper().semanticTarget(new SemanticDtos.MethodTarget(
                "src/OrderService.java", "com.example", "OrderService", "find", List.of("java.lang.String")));
        IssuedCandidate candidate = new IssuedCandidate(
                new CandidateHandle("candidate-2", new HandleBinding(new AnalysisRunId("run-1"),
                        new AnalysisAttemptId("attempt-1"), revisions), CandidateKind.SEMANTIC_TARGET),
                new SemanticTargetCandidate(repositoryId, revision, target, "Order lookup"));
        return new CapabilityExecutionContext(descriptor(name, CandidateKind.SEMANTIC_TARGET), List.of(candidate),
                "Trace orders", revisions);
    }

    private static CapabilityPolicy descriptor(String name, CandidateKind candidateKind) {
        return new CapabilityPolicy(name, "v1", Set.of(candidateKind), 0, 1);
    }

    private static TestClient testClient() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://semantic.test")
                .defaultHeader("X-Api-Token", "test-token");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(builder.build(), server);
    }

    private record TestClient(RestClient restClient, MockRestServiceServer server) {
    }
}
