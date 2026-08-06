package com.java.system.agent.codeintelligence.semantic.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Objects;

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

    public record ApiRouteLookupRequest(String apiPath, String httpMethod, String repoId, String expectedRevision) {
    }

    public record ApiRouteSuggestRequest(String apiPath, String httpMethod, String repoId, String expectedRevision,
                                         Integer limit) {
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
                                                  MethodTargetPayload target) {
    }

    public record AnalyzeIncomingCallGraphRequest(String repoId, String expectedRevision, Integer depth,
                                                  MethodTargetPayload target) {
    }

    public record MethodTarget(String sourceFile, String packageName, String className, String methodName,
                               List<String> parameterTypes) {

        @JsonCreator
        public static MethodTarget fromPayload(
                @JsonProperty("sourceType") SourceTypeIdentityPayload sourceType,
                @JsonProperty("methodName") String methodName,
                @JsonProperty("parameterTypes") List<String> parameterTypes) {
            SourceTypeIdentityPayload requiredSourceType = Objects.requireNonNull(
                    sourceType, "sourceType is required");
            JavaTypeIdentityPayload javaType = requiredSourceType.javaType();
            return new MethodTarget(requiredSourceType.sourceFile(), javaType.packageName(),
                    javaType.className(), methodName, parameterTypes);
        }
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
                            String traversalState, String dispatchKind, SourceRange declarationRange,
                            List<AvailableFollowUp> availableFollowUps) {

        public GraphNode {
            availableFollowUps = List.copyOf(Objects.requireNonNull(
                    availableFollowUps, "availableFollowUps are required"));
        }
    }

    public record GraphEdge(String callerNodeId, String calleeNodeId, SourceRange callSite,
                            String callExpression, String resolutionStrategy, String category,
                            List<String> evidence) {
    }

    public record GraphWarning(String code, String message, String nodeId, String callExpression,
                               SourceRange callSite, List<MethodTarget> candidates,
                               List<AvailableFollowUp> availableFollowUps) {

        public GraphWarning {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(
                    availableFollowUps, "availableFollowUps are required"));
        }
    }

    public record GraphError(String code, String message, String nodeId) {
    }

    public record SourceRange(String sourceFile, Position start, Position end) {
    }

    public record Position(Integer line, Integer character) {
    }

    /** Java 型別的 HTTP 識別資料 */
    public record JavaTypeIdentityPayload(String packageName, String className) {

        public JavaTypeIdentityPayload {
            packageName = Objects.requireNonNull(packageName, "packageName is required");
            className = Objects.requireNonNull(className, "className is required");
        }
    }

    /** 以來源檔案限定的 Java 型別 HTTP 識別資料 */
    public record SourceTypeIdentityPayload(JavaTypeIdentityPayload javaType, String sourceFile) {

        public SourceTypeIdentityPayload {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
            sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required");
        }
    }

    /** 正規方法目標 HTTP 資料 */
    public record MethodTargetPayload(SourceTypeIdentityPayload sourceType, String methodName,
                                      List<String> parameterTypes) {

        public MethodTargetPayload {
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
            methodName = Objects.requireNonNull(methodName, "methodName is required");
            parameterTypes = List.copyOf(Objects.requireNonNull(
                    parameterTypes, "parameterTypes are required"));
        }
    }

    /** 零基 UTF-16 半開文字範圍 HTTP 資料 */
    public record TextRangePayload(Position start, Position end) {

        public TextRangePayload {
            start = Objects.requireNonNull(start, "start is required");
            end = Objects.requireNonNull(end, "end is required");
        }
    }

    /** 含有來源檔案的可導覽文字範圍 HTTP 資料 */
    public record SourceRangePayload(String sourceFile, TextRangePayload range) {

        public SourceRangePayload {
            sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required");
            range = Objects.requireNonNull(range, "range is required");
        }
    }

    /** Java Semantic Service 回傳的 follow-up HTTP contract data，Agent runtime capability exposure 延至下一個 milestone */
    public record AvailableFollowUp(String operation, FollowUpApi api, AvailableFollowUpRequest request) {

        public AvailableFollowUp {
            operation = Objects.requireNonNull(operation, "operation is required");
            api = Objects.requireNonNull(api, "api is required");
            request = Objects.requireNonNull(request, "request is required");
        }
    }

    /** follow-up 固定 HTTP method、path 與 operationId */
    public record FollowUpApi(String method, String path, String operationId) {

        public FollowUpApi {
            method = Objects.requireNonNull(method, "method is required");
            path = Objects.requireNonNull(path, "path is required");
            operationId = Objects.requireNonNull(operationId, "operationId is required");
        }
    }

    /** graph 回應目前可回傳的封閉 typed follow-up request */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({
            @JsonSubTypes.Type(MethodSourceFollowUpRequest.class),
            @JsonSubTypes.Type(SourceSegmentFollowUpRequest.class)
    })
    public sealed interface AvailableFollowUpRequest permits MethodSourceFollowUpRequest,
            SourceSegmentFollowUpRequest {
    }

    /** GET_METHOD_SOURCE 的完整 HTTP request payload */
    public record MethodSourceFollowUpRequest(String repoId, String expectedRevision, MethodTargetPayload target)
            implements AvailableFollowUpRequest {

        public MethodSourceFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** GET_SOURCE_SEGMENT 的完整 HTTP request payload */
    public record SourceSegmentFollowUpRequest(
            String repoId,
            String expectedRevision,
            SourceRangePayload location,
            Integer contextLines) implements AvailableFollowUpRequest {

        public SourceSegmentFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            location = Objects.requireNonNull(location, "location is required");
            contextLines = Objects.requireNonNull(contextLines, "contextLines is required");
        }
    }

    public record ApiErrorResponse(String errorCode, String message, String repoId, String expectedRevision,
                                   String currentRevision, MethodTarget target, List<MethodTarget> candidates,
                                   String requestId) {
    }
}
