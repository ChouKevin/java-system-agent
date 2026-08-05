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
import com.java.system.agent.answering.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.RepositoryRevisionFailureCode;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.codeintelligence.planning.EntryPointType;
import com.java.system.agent.codeintelligence.planning.IncomingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.ListEntryPointsExecutionInput;
import com.java.system.agent.codeintelligence.planning.LookupApiRouteExecutionInput;
import com.java.system.agent.codeintelligence.planning.OutgoingCallGraphExecutionInput;
import com.java.system.agent.codeintelligence.planning.SuggestApiRouteExecutionInput;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 驗證 Agent 對 Java Semantic Service 的 live HTTP consumer contract
 */
@EnabledIfEnvironmentVariable(named = "M5_SEMANTIC_BASE_URL", matches = ".+")
class JavaSemanticServiceLiveContractIT {

    private static final String REPOSITORY_ID = requiredEnvironment("M5_REPO_ID");
    private static final String REVISION = requiredEnvironment("M5_EXPECTED_REVISION");
    private static final String SOURCE_FILE =
            "src/main/java/com/java/system/agent/codeintelligence/semantic/JavaSemanticServiceHttpAdapter.java";
    private static final String PACKAGE_NAME = "com.java.system.agent.codeintelligence.semantic";
    private static final String CLASS_NAME = "JavaSemanticServiceHttpAdapter";
    private static final String METHOD_NAME = "availableRepositories";
    private static final String UNKNOWN_REPOSITORY_ID = "m5-contract-missing-repository";
    private static final String UNKNOWN_REVISION = "0000000000000000000000000000000000000000";

    private final RestClient restClient = RestClient.builder()
            .baseUrl(requiredEnvironment("M5_SEMANTIC_BASE_URL"))
            .defaultHeader("X-Api-Token", requiredEnvironment("M5_SEMANTIC_API_TOKEN"))
            .build();
    private final JavaSemanticServiceHttpAdapter adapter = new JavaSemanticServiceHttpAdapter(restClient);

    @Test
    void executesTheM5ReadOnlyConsumerContractAgainstThePinnedRepository() {
        RepositoryId repositoryId = new RepositoryId(REPOSITORY_ID);
        RepositoryRevision revision = new RepositoryRevision(REVISION);

        assertThat(adapter.availableRepositories())
                .extracting(descriptor -> descriptor.repositoryId().value())
                .contains(REPOSITORY_ID);
        assertThat(adapter.currentRevision(repositoryId))
                .isEqualTo(new RepositoryRevisionResult.Ready(revision));
        assertThat(adapter.listEntryPoints(repositoryContext("codebase_list_entry_points", revision),
                new ListEntryPointsExecutionInput(EntryPointType.API)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.lookupApiRoute(repositoryContext("codebase_lookup_api_route", revision),
                new LookupApiRouteExecutionInput("/v1/repositories", null)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.suggestApiRoute(repositoryContext("codebase_suggest_api_route", revision),
                new SuggestApiRouteExecutionInput("/v1/repositories", null, 3)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.outgoingCallGraph(targetContext("codebase_outgoing_call_graph", revision),
                new OutgoingCallGraphExecutionInput(1)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        assertThat(adapter.incomingCallGraph(targetContext("codebase_incoming_call_graph", revision),
                new IncomingCallGraphExecutionInput(1)))
                .isInstanceOf(CapabilityExecutionResult.Succeeded.class);

        SemanticDtos.OutgoingCallGraphResponse response = rawOutgoingCallGraph(revision);
        assertOutgoingGraphContract(response);
    }

    @Test
    void mapsRepresentativeLiveFailuresToProviderNeutralResults() {
        RepositoryId repositoryId = new RepositoryId(REPOSITORY_ID);
        JavaSemanticServiceHttpAdapter unauthorizedAdapter = new JavaSemanticServiceHttpAdapter(RestClient.builder()
                .baseUrl(requiredEnvironment("M5_SEMANTIC_BASE_URL"))
                .defaultHeader("X-Api-Token", "m5-contract-invalid-token")
                .build());

        assertThat(unauthorizedAdapter.currentRevision(repositoryId))
                .isInstanceOfSatisfying(RepositoryRevisionResult.Failed.class,
                        failed -> assertThat(failed.failure().code()).isEqualTo(RepositoryRevisionFailureCode.FORBIDDEN));
        assertThat(adapter.currentRevision(new RepositoryId(UNKNOWN_REPOSITORY_ID)))
                .isInstanceOfSatisfying(RepositoryRevisionResult.Failed.class,
                        failed -> assertThat(failed.failure().code())
                                .isEqualTo(RepositoryRevisionFailureCode.REPOSITORY_NOT_FOUND));

        RepositoryRevision wrongRevision = new RepositoryRevision(UNKNOWN_REVISION);
        assertThat(adapter.listEntryPoints(repositoryContext("codebase_list_entry_points", wrongRevision),
                new ListEntryPointsExecutionInput(EntryPointType.API)))
                .isInstanceOfSatisfying(CapabilityExecutionResult.Failed.class,
                        failed -> assertThat(failed.failure().code())
                                .isEqualTo(CapabilityExecutionFailureCode.REVISION_CONFLICT));
        SemanticDtos.ApiErrorResponse revisionError = rawRevisionConflict(wrongRevision);
        assertThat(revisionError.errorCode()).isEqualTo("REPOSITORY_REVISION_MISMATCH");
        assertThat(revisionError.expectedRevision()).isEqualTo(UNKNOWN_REVISION);
        assertThat(revisionError.currentRevision()).isEqualTo(REVISION);

        assertThat(adapter.lookupApiRoute(repositoryContext("codebase_lookup_api_route",
                new RepositoryRevision(REVISION)), new LookupApiRouteExecutionInput("", null)))
                .isInstanceOfSatisfying(CapabilityExecutionResult.Failed.class,
                        failed -> assertThat(failed.failure().code())
                                .isEqualTo(CapabilityExecutionFailureCode.DEPENDENCY_FAILURE));
    }

    private SemanticDtos.OutgoingCallGraphResponse rawOutgoingCallGraph(RepositoryRevision revision) {
        SemanticDtos.AnalyzeOutgoingCallGraphRequest request = new SemanticDtos.AnalyzeOutgoingCallGraphRequest(
                REPOSITORY_ID, revision.value(), 1, methodTarget());
        SemanticDtos.OutgoingCallGraphResponse response = restClient.post()
                .uri("/v1/analyses/call-graphs/outgoing")
                .body(request)
                .retrieve()
                .body(SemanticDtos.OutgoingCallGraphResponse.class);
        return Objects.requireNonNull(response, "live outgoing graph response must not be null");
    }

    private SemanticDtos.ApiErrorResponse rawRevisionConflict(RepositoryRevision expectedRevision) {
        SemanticDtos.ApiErrorResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/repositories/{repoId}/entry-points")
                        .queryParam("expectedRevision", expectedRevision.value())
                        .queryParam("types", EntryPointType.API.name())
                        .build(REPOSITORY_ID))
                .exchange((request, clientResponse) -> clientResponse.bodyTo(SemanticDtos.ApiErrorResponse.class));
        return Objects.requireNonNull(response, "live revision conflict response must not be null");
    }

    private void assertOutgoingGraphContract(SemanticDtos.OutgoingCallGraphResponse response) {
        assertThat(response.analyzedRevision()).isEqualTo(REVISION);
        assertThat(response.traversal().requestedDepth()).isEqualTo(1);
        assertThat(response.traversal().expandedNodeCount()).isGreaterThanOrEqualTo(0);
        assertThat(response.traversal().nodeBudget()).isGreaterThanOrEqualTo(0);
        assertThat(StringUtils.hasText(response.traversal().limitReason())).isTrue();

        SemanticDtos.GraphNode root = response.nodes().stream()
                .filter(node -> response.rootNodeId().equals(node.nodeId()))
                .findFirst()
                .orElseThrow();
        SemanticDtos.MethodTarget rootTarget = Objects.requireNonNull(root.target(), "root target must not be null");
        assertThat(rootTarget)
                .extracting(SemanticDtos.MethodTarget::sourceFile, SemanticDtos.MethodTarget::packageName,
                        SemanticDtos.MethodTarget::className, SemanticDtos.MethodTarget::methodName,
                        SemanticDtos.MethodTarget::parameterTypes)
                .containsExactly(SOURCE_FILE, PACKAGE_NAME, CLASS_NAME, METHOD_NAME, List.of());

        for (SemanticDtos.GraphNode node : response.nodes()) {
            assertRangeWhenPresent(node.declarationRange());
            assertFollowUps(node.availableFollowUps());
        }
        for (SemanticDtos.GraphEdge edge : response.edges()) {
            assertRange(Objects.requireNonNull(edge.callSite(), "graph call site must not be null"));
        }
        for (SemanticDtos.GraphWarning warning : response.warnings()) {
            assertRangeWhenPresent(warning.callSite());
            assertFollowUps(warning.availableFollowUps());
        }
    }

    private void assertRangeWhenPresent(SemanticDtos.SourceRange range) {
        if (Objects.nonNull(range)) {
            assertRange(range);
        }
    }

    private void assertRange(SemanticDtos.SourceRange range) {
        SemanticDtos.Position start = Objects.requireNonNull(range.start(), "range start must not be null");
        SemanticDtos.Position end = Objects.requireNonNull(range.end(), "range end must not be null");
        assertThat(start.line()).isGreaterThanOrEqualTo(0);
        assertThat(start.character()).isGreaterThanOrEqualTo(0);
        assertThat(end.line()).isGreaterThanOrEqualTo(start.line());
        assertThat(end.character()).isGreaterThanOrEqualTo(0);
        if (end.line().equals(start.line())) {
            assertThat(end.character()).isGreaterThanOrEqualTo(start.character());
        }
    }

    private void assertFollowUps(List<SemanticDtos.AvailableFollowUp> followUps) {
        for (SemanticDtos.AvailableFollowUp followUp : followUps) {
            assertThat(followUp.operation()).isNotBlank();
            assertThat(followUp.api().method()).isNotBlank();
            assertThat(followUp.api().path()).isNotBlank();
            assertThat(followUp.api().operationId()).isNotBlank();
            switch (followUp.request()) {
                case SemanticDtos.MethodSourceFollowUpRequest request -> assertFollowUpScope(
                        request.repoId(), request.expectedRevision());
                case SemanticDtos.SourceSegmentFollowUpRequest request -> assertFollowUpScope(
                        request.repoId(), request.expectedRevision());
            }
        }
    }

    private void assertFollowUpScope(String repositoryId, String expectedRevision) {
        assertThat(repositoryId).isEqualTo(REPOSITORY_ID);
        assertThat(expectedRevision).isEqualTo(REVISION);
    }

    private CapabilityExecutionContext repositoryContext(String name, RepositoryRevision revision) {
        RepositoryId repositoryId = new RepositoryId(REPOSITORY_ID);
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        IssuedCandidate candidate = new IssuedCandidate(
                new CandidateHandle("live-repository-candidate", new HandleBinding(new AnalysisRunId("live-run"),
                        new AnalysisAttemptId("live-attempt"), revisions), CandidateKind.REPOSITORY),
                new RepositoryCandidate(repositoryId, "Live contract repository"));
        return new CapabilityExecutionContext(descriptor(name, CandidateKind.REPOSITORY), List.of(candidate),
                "Verify Java Semantic Service consumer contract", revisions);
    }

    private CapabilityExecutionContext targetContext(String name, RepositoryRevision revision) {
        RepositoryId repositoryId = new RepositoryId(REPOSITORY_ID);
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        SemanticTarget target = new JavaSemanticResultMapper().semanticTarget(methodTarget());
        IssuedCandidate candidate = new IssuedCandidate(
                new CandidateHandle("live-target-candidate", new HandleBinding(new AnalysisRunId("live-run"),
                        new AnalysisAttemptId("live-attempt"), revisions), CandidateKind.SEMANTIC_TARGET),
                new SemanticTargetCandidate(repositoryId, revision, target, "Live contract target"));
        return new CapabilityExecutionContext(descriptor(name, CandidateKind.SEMANTIC_TARGET), List.of(candidate),
                "Verify Java Semantic Service call graph contract", revisions);
    }

    private SemanticDtos.MethodTarget methodTarget() {
        return new SemanticDtos.MethodTarget(SOURCE_FILE, PACKAGE_NAME, CLASS_NAME, METHOD_NAME, List.of());
    }

    private CapabilityPolicy descriptor(String name, CandidateKind candidateKind) {
        return new CapabilityPolicy(name, "v1", Set.of(candidateKind), 0, 1);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        Assert.state(StringUtils.hasText(value), () -> name + " must be set for the live semantic contract");
        return value;
    }
}
