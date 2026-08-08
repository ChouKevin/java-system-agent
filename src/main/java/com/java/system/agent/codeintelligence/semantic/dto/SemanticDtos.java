package com.java.system.agent.codeintelligence.semantic.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
                            String traversalState, String dispatchKind, TextRangePayload declarationRange,
                            List<AvailableFollowUp> availableFollowUps) {

        public GraphNode {
            availableFollowUps = List.copyOf(Objects.requireNonNull(
                    availableFollowUps, "availableFollowUps are required"));
        }
    }

    public record GraphEdge(String callerNodeId, String calleeNodeId, SourceRangePayload callSite,
                            String callExpression, String resolutionStrategy, String category,
                            List<String> evidence, List<AvailableFollowUp> availableFollowUps) {

        public GraphEdge {
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(
                    availableFollowUps, "availableFollowUps are required"));
        }

        public GraphEdge(String callerNodeId, String calleeNodeId, SourceRangePayload callSite,
                         String callExpression, String resolutionStrategy, String category,
                         List<String> evidence) {
            this(callerNodeId, calleeNodeId, callSite, callExpression, resolutionStrategy, category,
                    evidence, List.of());
        }
    }

    public record GraphWarning(String code, String message, String nodeId, String callExpression,
                               SourceRangePayload callSite, List<MethodTarget> candidates,
                               List<AvailableFollowUp> availableFollowUps) {

        public GraphWarning {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(
                    availableFollowUps, "availableFollowUps are required"));
        }
    }

    public record GraphError(String code, String message, String nodeId) {
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
    public record SourceTypeIdentityPayload(JavaTypeIdentityPayload javaType, String sourceFile)
            implements InternalReferenceIdentity {

        public SourceTypeIdentityPayload {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
            sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required");
        }
    }

    /** 正規方法目標 HTTP 資料 */
    public record MethodTargetPayload(SourceTypeIdentityPayload sourceType, String methodName,
                                      List<String> parameterTypes) implements FollowUpTarget, InternalReferenceIdentity {

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

    /** 型別直接成員或方法範圍成員的封閉 identity */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "scope")
    @JsonSubTypes({@JsonSubTypes.Type(value = SourceMemberIdentityPayload.TypeMember.class, name = "TYPE"),
            @JsonSubTypes.Type(value = SourceMemberIdentityPayload.MethodScoped.class, name = "METHOD")})
    public sealed interface SourceMemberIdentityPayload extends InternalReferenceIdentity permits SourceMemberIdentityPayload.TypeMember,
            SourceMemberIdentityPayload.MethodScoped {

        record TypeMember(SourceTypeIdentityPayload ownerType, String name) implements SourceMemberIdentityPayload {
            public TypeMember {
                ownerType = Objects.requireNonNull(ownerType, "ownerType is required");
                name = Objects.requireNonNull(name, "name is required");
            }
        }

        record MethodScoped(MethodTargetPayload declaringMethod, TextRangePayload declarationRange, String name)
                implements SourceMemberIdentityPayload {
            public MethodScoped {
                declaringMethod = Objects.requireNonNull(declaringMethod, "declaringMethod is required");
                declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
                name = Objects.requireNonNull(name, "name is required");
            }
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

    /** graph 與 discovery 回應可回傳的封閉 typed follow-up request */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({
            @JsonSubTypes.Type(TargetFollowUpRequest.class),
            @JsonSubTypes.Type(DiscoverMethodImplementationsFollowUpRequest.class),
            @JsonSubTypes.Type(IdentityFollowUpRequest.class),
            @JsonSubTypes.Type(TypeMembersFollowUpRequest.class),
            @JsonSubTypes.Type(DiscoverConceptsFollowUpRequest.class),
            @JsonSubTypes.Type(DiscoverEventListenersFollowUpRequest.class),
            @JsonSubTypes.Type(ResolveSourceSymbolFollowUpRequest.class),
            @JsonSubTypes.Type(SourceSegmentFollowUpRequest.class)
    })
    public sealed interface AvailableFollowUpRequest permits TargetFollowUpRequest,
            DiscoverMethodImplementationsFollowUpRequest,
            IdentityFollowUpRequest, TypeMembersFollowUpRequest,
            DiscoverConceptsFollowUpRequest, DiscoverEventListenersFollowUpRequest,
            ResolveSourceSymbolFollowUpRequest,
            SourceSegmentFollowUpRequest {

        String repoId();

        String expectedRevision();
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

    /** target follow-up 的共用 request，operation validator 會要求精確 optional 欄位組合 */
    public record TargetFollowUpRequest(String repoId, String expectedRevision, FollowUpTarget target,
                                        Optional<Integer> depth, Optional<Integer> offset, Optional<Integer> limit)
            implements AvailableFollowUpRequest {
        public TargetFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            target = Objects.requireNonNull(target, "target is required");
            depth = Optional.ofNullable(depth).orElse(Optional.empty());
            offset = Optional.ofNullable(offset).orElse(Optional.empty());
            limit = Optional.ofNullable(limit).orElse(Optional.empty());
        }
    }

    /** 方法實作探索的完整 follow-up request */
    public record DiscoverMethodImplementationsFollowUpRequest(String repoId, String expectedRevision,
                                                                MethodTargetPayload declarationTarget)
            implements AvailableFollowUpRequest {
        public DiscoverMethodImplementationsFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            declarationTarget = Objects.requireNonNull(declarationTarget, "declarationTarget is required");
        }
    }

    /** RESOLVE_CONCEPT 與 GET_EVIDENCE_SOURCE 共用的完整 follow-up request */
    public record IdentityFollowUpRequest(String repoId, String expectedRevision, FollowUpIdentity identity)
            implements AvailableFollowUpRequest {
        public IdentityFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    /** GET_TYPE_MEMBERS 與 DISCOVER_TYPE_MEMBERS 共用的完整 follow-up request */
    public record TypeMembersFollowUpRequest(String repoId, String expectedRevision,
                                                      SourceTypeIdentityPayload sourceType, List<String> memberKinds,
                                                      Optional<String> namePrefix, Integer offset, Integer limit)
            implements AvailableFollowUpRequest {
        public TypeMembersFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
            memberKinds = List.copyOf(Objects.requireNonNull(memberKinds, "memberKinds are required"));
            namePrefix = Optional.ofNullable(namePrefix).orElse(Optional.empty());
            offset = Objects.requireNonNull(offset, "offset is required");
            limit = Objects.requireNonNull(limit, "limit is required");
        }
    }

    /** 概念探索續頁的完整 follow-up request */
    public record DiscoverConceptsFollowUpRequest(String repoId, String expectedRevision,
                                                  List<ConceptSearchTermPayload> terms, List<String> kinds,
                                                  String operator, Optional<String> packagePrefix,
                                                  Integer offset, Integer limit) implements AvailableFollowUpRequest {
        public DiscoverConceptsFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            terms = List.copyOf(Objects.requireNonNull(terms, "terms are required"));
            kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
            operator = Objects.requireNonNull(operator, "operator is required");
            packagePrefix = Optional.ofNullable(packagePrefix).orElse(Optional.empty());
            offset = Objects.requireNonNull(offset, "offset is required");
            limit = Objects.requireNonNull(limit, "limit is required");
        }
    }

    /** 事件監聽器續頁的完整 follow-up request */
    public record DiscoverEventListenersFollowUpRequest(String repoId, String expectedRevision, String eventType,
                                                        Integer offset, Integer limit) implements AvailableFollowUpRequest {
        public DiscoverEventListenersFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            eventType = Objects.requireNonNull(eventType, "eventType is required");
            offset = Objects.requireNonNull(offset, "offset is required");
            limit = Objects.requireNonNull(limit, "limit is required");
        }
    }

    /** 來源符號解析的完整 follow-up request */
    public record ResolveSourceSymbolFollowUpRequest(String repoId, String expectedRevision,
                                                     SourceSymbolContextPayload context, String symbol,
                                                     Optional<Position> position) implements AvailableFollowUpRequest {
        public ResolveSourceSymbolFollowUpRequest {
            repoId = Objects.requireNonNull(repoId, "repoId is required");
            expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            context = Objects.requireNonNull(context, "context is required");
            symbol = Objects.requireNonNull(symbol, "symbol is required");
            position = Optional.ofNullable(position).orElse(Optional.empty());
        }
    }

    /** 可由欄位集合區分的 concept 或 evidence identity */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(ConceptIdentityPayload.class), @JsonSubTypes.Type(EvidenceSourceIdentityPayload.class)})
    public sealed interface FollowUpIdentity permits ConceptIdentityPayload, EvidenceSourceIdentityPayload {
    }

    /** provider 概念 identity 的封閉外觀 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConceptIdentityPayload(String kind, MethodTargetPayload target) implements FollowUpIdentity {
        public ConceptIdentityPayload {
            kind = Objects.requireNonNull(kind, "kind is required");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** provider 概念搜尋詞 */
    public record ConceptSearchTermPayload(String value, String matchMode) {
        public ConceptSearchTermPayload {
            value = Objects.requireNonNull(value, "value is required");
            matchMode = Objects.requireNonNull(matchMode, "matchMode is required");
        }
    }

    /** 來源符號解析 context */
    public record SourceSymbolContextPayload(JavaTypeIdentityPayload javaType, Optional<String> sourceFile,
                                             Optional<SourceSymbolMethodContextPayload> method) {
        public SourceSymbolContextPayload {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
            sourceFile = Optional.ofNullable(sourceFile).orElse(Optional.empty());
            method = Optional.ofNullable(method).orElse(Optional.empty());
        }
    }

    /** 來源符號的可選方法 context */
    public record SourceSymbolMethodContextPayload(String name, List<String> parameterTypes) {
        public SourceSymbolMethodContextPayload {
            name = Objects.requireNonNull(name, "name is required");
            parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes are required"));
        }
    }

    /** 需要 exact target 的 follow-up 共用封閉 identity */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(MethodTargetPayload.class),
            @JsonSubTypes.Type(InternalReferenceFollowUpTarget.class)})
    public sealed interface FollowUpTarget permits MethodTargetPayload, InternalReferenceFollowUpTarget {
    }

    /** internal reference target 的 typed identity */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(SourceTypeIdentityPayload.class), @JsonSubTypes.Type(MethodTargetPayload.class),
            @JsonSubTypes.Type(SourceMemberIdentityPayload.class)})
    public sealed interface InternalReferenceIdentity permits SourceTypeIdentityPayload, MethodTargetPayload,
            SourceMemberIdentityPayload {
    }

    /** provider internal reference target 的封閉外觀 */
    public record InternalReferenceFollowUpTarget(String kind, InternalReferenceIdentity identity)
            implements FollowUpTarget {
        public InternalReferenceFollowUpTarget {
            kind = Objects.requireNonNull(kind, "kind is required");
            identity = Objects.requireNonNull(identity, "identity is required");
            boolean matches = ("TYPE".equals(kind) && identity instanceof SourceTypeIdentityPayload)
                    || ("METHOD".equals(kind) && identity instanceof MethodTargetPayload)
                    || ("MEMBER".equals(kind) && identity instanceof SourceMemberIdentityPayload);
            if (!matches) {
                throw new IllegalArgumentException("internal reference identity does not match kind");
            }
        }
    }

    /** provider evidence source identity */
    public record EvidenceSourceIdentityPayload(String kind, Optional<MethodTargetPayload> statementIdentity,
                                                Optional<SourceTypeIdentityPayload> fragmentIdentity) implements FollowUpIdentity {
        public EvidenceSourceIdentityPayload {
            kind = Objects.requireNonNull(kind, "kind is required");
            statementIdentity = Optional.ofNullable(statementIdentity).orElse(Optional.empty());
            fragmentIdentity = Optional.ofNullable(fragmentIdentity).orElse(Optional.empty());
        }
    }

    public record ApiErrorResponse(String errorCode, String message, String repoId, String expectedRevision,
                                   String currentRevision, MethodTarget target, List<MethodTarget> candidates,
                                   String requestId) {
    }
}
