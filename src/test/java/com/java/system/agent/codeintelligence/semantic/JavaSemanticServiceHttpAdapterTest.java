package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
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
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailureCode;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;
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
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

/**
 * Java Semantic Service read-only HTTP adapter 的可觀察合約測試
 */
class JavaSemanticServiceHttpAdapterTest {

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
                {"status":"SUCCESS","analyzedRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","rootNodeId":"root","traversal":{"requestedDepth":1,"expandedNodeCount":0,"nodeBudget":0,"rootDirectCallsComplete":true,"limitReason":"NONE"},"nodes":[{"nodeId":"root","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"OrderService"},"sourceFile":"src/OrderService.java"},"methodName":"find","parameterTypes":["java.lang.String"]},"externalSymbol":null,"contentState":"FULL_SOURCE","traversalState":"EXPANDED","dispatchKind":"SYNCHRONOUS","declarationRange":null,"availableFollowUps":[]}],"edges":[],"warnings":[],"errors":[]}
                """;
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
