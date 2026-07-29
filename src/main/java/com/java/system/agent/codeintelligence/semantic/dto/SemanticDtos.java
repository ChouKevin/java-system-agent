package com.java.system.agent.codeintelligence.semantic.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Java Semantic Service v1 HTTP 文件使用的窄 DTO 集合
 */
public final class SemanticDtos {

    private SemanticDtos() {
    }

    public record RepositoryStatusResponse(String repoId, String mode, String displayName,
                                           String currentBranch, String currentRevision, Boolean cloned) {
    }

    public record EntryPointsResponse(String repoId, String analyzedRevision,
                                      List<EntryPointClassResponse> entryPoints) {
    }

    public record EntryPointClassResponse(String className, String packageName, String packagePath,
                                          String description, List<String> basePaths,
                                          List<EntryPointMethodResponse> methods) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ApiEntryPointMethodResponse.class, name = "API"),
            @JsonSubTypes.Type(value = MqEntryPointMethodResponse.class, name = "MQ"),
            @JsonSubTypes.Type(value = ScheduleEntryPointMethodResponse.class, name = "SCHEDULE")
    })
    public sealed interface EntryPointMethodResponse permits ApiEntryPointMethodResponse,
            MqEntryPointMethodResponse, ScheduleEntryPointMethodResponse {

        String name();

        String description();

        String type();

        MethodTargetResolutionResponse analysisTarget();
    }

    public record ApiEntryPointMethodResponse(String name, String description, String type, String apiUrl,
                                              List<String> httpMethods, List<String> swaggerDescriptions,
                                              MethodTargetResolutionResponse analysisTarget)
            implements EntryPointMethodResponse {
    }

    public record MqEntryPointMethodResponse(String name, String description, String type, String broker,
                                             List<String> destinations,
                                             MethodTargetResolutionResponse analysisTarget)
            implements EntryPointMethodResponse {
    }

    public record ScheduleEntryPointMethodResponse(String name, String description, String type,
                                                   String triggerKind, String triggerValue,
                                                   MethodTargetResolutionResponse analysisTarget)
            implements EntryPointMethodResponse {
    }

    public record ApiRouteLookupRequest(String apiPath, String httpMethod, String repoScope) {
    }

    public record ApiRouteSuggestRequest(String apiPath, String httpMethod, String repoScope, Integer limit) {
    }

    public record ApiRouteCandidatesResponse(List<ApiRouteCandidateResponse> candidates,
                                             List<ApiRouteObservationResponse> observations) {
    }

    public record ApiRouteCandidateResponse(String repoId, String analyzedRevision, String httpMethod,
                                            String routeTemplate, String packageName, String className,
                                            String methodName, MethodTargetResolutionResponse analysisTarget,
                                            List<String> matchReasons) {
    }

    public record ApiRouteObservationResponse(String code, String description) {
    }

    public record AnalyzeOutgoingCallGraphRequest(String repoId, String expectedRevision, Integer depth,
                                                  MethodTarget target) {
    }

    public record AnalyzeIncomingCallGraphRequest(String repoId, String expectedRevision, Integer depth,
                                                  MethodTarget target) {
    }

    public record MethodTarget(String sourceFile, String packageName, String className, String methodName,
                               List<String> parameterTypes) {
    }

    public record MethodTargetResolutionResponse(String status, MethodTarget target,
                                                 List<MethodTarget> candidates, String reasonCode) {
    }

    public record OutgoingCallGraphResponse(String status, String analyzedRevision, String rootNodeId,
                                            GraphTraversal traversal, List<GraphNode> nodes,
                                            List<GraphEdge> edges, List<GraphWarning> warnings,
                                            List<GraphError> errors) {
    }

    public record IncomingCallGraphResponse(String status, String analyzedRevision, String rootNodeId,
                                            GraphTraversal traversal, List<GraphNode> nodes,
                                            List<GraphEdge> edges, List<GraphWarning> warnings,
                                            List<GraphError> errors) {
    }

    public record GraphTraversal(Integer requestedDepth, Integer expandedNodeCount, Integer nodeBudget,
                                 Boolean rootDirectCallsComplete, String limitReason) {
    }

    public record GraphNode(String nodeId, MethodTarget target, String externalSymbol, String contentState,
                            String traversalState, String dispatchKind, String methodBody,
                            SourceRange declarationRange) {
    }

    public record GraphEdge(String callerNodeId, String calleeNodeId, SourceRange callSite,
                            String callExpression, String resolutionStrategy, String category,
                            List<String> evidence) {
    }

    public record GraphWarning(String code, String message, String nodeId, String callExpression,
                               SourceRange callSite, List<MethodTarget> candidates) {
    }

    public record GraphError(String code, String message, String nodeId) {
    }

    public record SourceRange(String sourceFile, Position start, Position end) {
    }

    public record Position(Integer line, Integer character) {
    }

    public record ApiErrorResponse(String errorCode, String message, String repoId, String expectedRevision,
                                   String currentRevision, MethodTarget target, List<MethodTarget> candidates,
                                   String requestId) {
    }
}
