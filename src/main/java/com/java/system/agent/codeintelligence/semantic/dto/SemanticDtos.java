package com.java.system.agent.codeintelligence.semantic.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.Min;

import java.util.List;
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

    public record Position(@Min(0) Integer line, @Min(0) Integer character) {
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
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "scope", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = SourceMemberIdentityPayload.TypeMember.class, name = "TYPE"),
            @JsonSubTypes.Type(value = SourceMemberIdentityPayload.MethodScoped.class, name = "METHOD")})
    public sealed interface SourceMemberIdentityPayload extends InternalReferenceIdentity permits SourceMemberIdentityPayload.TypeMember,
            SourceMemberIdentityPayload.MethodScoped {

        record TypeMember(String scope, SourceTypeIdentityPayload ownerType, String name)
                implements SourceMemberIdentityPayload, ConceptIdentityTargetPayload {
            public TypeMember {
                scope = requiredKind(scope, "TYPE");
                ownerType = Objects.requireNonNull(ownerType, "ownerType is required");
                name = Objects.requireNonNull(name, "name is required");
            }
        }

        record MethodScoped(String scope, MethodTargetPayload declaringMethod, TextRangePayload declarationRange, String name)
                implements SourceMemberIdentityPayload, ConceptIdentityTargetPayload {
            public MethodScoped {
                scope = requiredKind(scope, "METHOD");
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
    @JsonSubTypes({@JsonSubTypes.Type(ConceptFollowUpIdentity.class),
            @JsonSubTypes.Type(EvidenceSourceFollowUpIdentity.class)})
    public sealed interface FollowUpIdentity permits ConceptFollowUpIdentity, EvidenceSourceFollowUpIdentity {
    }

    public sealed interface ConceptIdentityPayload permits ConceptFollowUpIdentity {
    }

    /** ten provider concept kinds 的單一 typed superset，validator 依 kind 收緊欄位組合 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConceptFollowUpIdentity(String kind, Optional<SourceTypeIdentityPayload> sourceType,
                                          Optional<MethodTargetPayload> target,
                                          Optional<ConceptIdentityTargetPayload> identity,
                                          Optional<DeclarationSubjectPayload> declaration,
                                          Optional<AnnotationTypePayload> annotationType,
                                          Optional<DeclarationSubjectPayload> owner,
                                          Optional<TypeUsageLocationPayload> location,
                                          Optional<List<TypeUsagePathPayload>> path,
                                          Optional<ReferencedTypePayload> referencedType,
                                          Optional<String> httpVerb, Optional<String> route,
                                          Optional<String> broker, Optional<String> destination,
                                          Optional<String> triggerKind, Optional<String> triggerValue)
            implements FollowUpIdentity, ConceptIdentityPayload {
        public ConceptFollowUpIdentity {
            kind = Objects.requireNonNull(kind, "kind is required");
            sourceType = optional(sourceType);
            target = optional(target);
            identity = optional(identity);
            declaration = optional(declaration);
            annotationType = optional(annotationType);
            owner = optional(owner);
            location = optional(location);
            path = optional(path).map(List::copyOf);
            referencedType = optional(referencedType);
            httpVerb = optional(httpVerb);
            route = optional(route);
            broker = optional(broker);
            destination = optional(destination);
            triggerKind = optional(triggerKind);
            triggerValue = optional(triggerValue);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(SourceMemberIdentityPayload.TypeMember.class),
            @JsonSubTypes.Type(SourceMemberIdentityPayload.MethodScoped.class),
            @JsonSubTypes.Type(MapperStatementKeyPayload.class),
            @JsonSubTypes.Type(MapperStatementIdentityPayload.class)})
    public sealed interface ConceptIdentityTargetPayload permits SourceMemberIdentityPayload.TypeMember,
            SourceMemberIdentityPayload.MethodScoped, MapperStatementKeyPayload, MapperStatementIdentityPayload {
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

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TypeDeclarationSubjectPayload.class, name = "TYPE"),
            @JsonSubTypes.Type(value = ResolvedMethodDeclarationSubjectPayload.class, name = "METHOD"),
            @JsonSubTypes.Type(value = UnresolvedMethodDeclarationSubjectPayload.class, name = "METHOD_UNRESOLVED"),
            @JsonSubTypes.Type(value = FieldDeclarationSubjectPayload.class, name = "FIELD")
    })
    public sealed interface DeclarationSubjectPayload permits TypeDeclarationSubjectPayload,
            ResolvedMethodDeclarationSubjectPayload, UnresolvedMethodDeclarationSubjectPayload,
            FieldDeclarationSubjectPayload {
    }

    public record TypeDeclarationSubjectPayload(String kind, SourceTypeIdentityPayload sourceType)
            implements DeclarationSubjectPayload {
        public TypeDeclarationSubjectPayload {
            kind = requiredKind(kind, "TYPE");
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
        }
    }

    public record ResolvedMethodDeclarationSubjectPayload(String kind, MethodTargetPayload target)
            implements DeclarationSubjectPayload {
        public ResolvedMethodDeclarationSubjectPayload {
            kind = requiredKind(kind, "METHOD");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    public record UnresolvedMethodDeclarationSubjectPayload(String kind, MethodTargetPayload target)
            implements DeclarationSubjectPayload {
        public UnresolvedMethodDeclarationSubjectPayload {
            kind = requiredKind(kind, "METHOD_UNRESOLVED");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    public record FieldDeclarationSubjectPayload(String kind, SourceMemberIdentityPayload identity)
            implements DeclarationSubjectPayload {
        public FieldDeclarationSubjectPayload {
            kind = requiredKind(kind, "FIELD");
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "status", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ResolvedAnnotationTypePayload.class, name = "RESOLVED"),
            @JsonSubTypes.Type(value = UnresolvedAnnotationTypePayload.class, name = "UNRESOLVED")
    })
    public sealed interface AnnotationTypePayload permits ResolvedAnnotationTypePayload,
            UnresolvedAnnotationTypePayload {
    }

    public record ResolvedAnnotationTypePayload(String status, JavaTypeIdentityPayload javaType)
            implements AnnotationTypePayload {
        public ResolvedAnnotationTypePayload {
            status = requiredKind(status, "RESOLVED");
            javaType = Objects.requireNonNull(javaType, "javaType is required");
        }
    }

    public record UnresolvedAnnotationTypePayload(String status, String writtenName)
            implements AnnotationTypePayload {
        public UnresolvedAnnotationTypePayload {
            status = requiredKind(status, "UNRESOLVED");
            writtenName = Objects.requireNonNull(writtenName, "writtenName is required");
        }
    }

    public record TypeUsageLocationPayload(String slot, Integer index) {
        public TypeUsageLocationPayload {
            slot = Objects.requireNonNull(slot, "slot is required");
            index = Objects.requireNonNull(index, "index is required");
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TypeArgumentPathPayload.class, name = "TYPE_ARGUMENT"),
            @JsonSubTypes.Type(value = WildcardExtendsBoundPathPayload.class, name = "WILDCARD_EXTENDS_BOUND"),
            @JsonSubTypes.Type(value = WildcardSuperBoundPathPayload.class, name = "WILDCARD_SUPER_BOUND"),
            @JsonSubTypes.Type(value = TypeVariableBoundPathPayload.class, name = "TYPE_VARIABLE_BOUND")
    })
    public sealed interface TypeUsagePathPayload permits TypeArgumentPathPayload,
            WildcardExtendsBoundPathPayload, WildcardSuperBoundPathPayload, TypeVariableBoundPathPayload {
    }

    public record TypeArgumentPathPayload(String kind, Integer index) implements TypeUsagePathPayload {
        public TypeArgumentPathPayload {
            kind = requiredKind(kind, "TYPE_ARGUMENT");
            index = Objects.requireNonNull(index, "index is required");
        }
    }

    public record WildcardExtendsBoundPathPayload(String kind) implements TypeUsagePathPayload {
        public WildcardExtendsBoundPathPayload {
            kind = requiredKind(kind, "WILDCARD_EXTENDS_BOUND");
        }
    }

    public record WildcardSuperBoundPathPayload(String kind) implements TypeUsagePathPayload {
        public WildcardSuperBoundPathPayload {
            kind = requiredKind(kind, "WILDCARD_SUPER_BOUND");
        }
    }

    public record TypeVariableBoundPathPayload(String kind, Integer index) implements TypeUsagePathPayload {
        public TypeVariableBoundPathPayload {
            kind = requiredKind(kind, "TYPE_VARIABLE_BOUND");
            index = Objects.requireNonNull(index, "index is required");
        }
    }

    public record ReferencedTypePayload(JavaTypeIdentityPayload javaType, Integer arrayDimensions) {
        public ReferencedTypePayload {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
            arrayDimensions = Objects.requireNonNull(arrayDimensions, "arrayDimensions is required");
        }
    }

    public record MapperStatementKeyPayload(String namespace, String statementId) implements ConceptIdentityTargetPayload {
        public MapperStatementKeyPayload {
            namespace = Objects.requireNonNull(namespace, "namespace is required");
            statementId = Objects.requireNonNull(statementId, "statementId is required");
        }
    }

    public record MapperStatementIdentityPayload(MapperStatementKeyPayload statementKey, String resourcePath,
                                                 Optional<String> databaseId, Integer documentOrdinal,
                                                 String representation) implements ConceptIdentityTargetPayload {
        public MapperStatementIdentityPayload {
            statementKey = Objects.requireNonNull(statementKey, "statementKey is required");
            resourcePath = Objects.requireNonNull(resourcePath, "resourcePath is required");
            databaseId = Optional.ofNullable(databaseId).orElse(Optional.empty());
            documentOrdinal = Objects.requireNonNull(documentOrdinal, "documentOrdinal is required");
            representation = Objects.requireNonNull(representation, "representation is required");
        }
    }

    public record MapperFragmentIdentityPayload(String namespace, String fragmentId, String resourcePath,
                                                Integer documentOrdinal, String representation) {
        public MapperFragmentIdentityPayload {
            namespace = Objects.requireNonNull(namespace, "namespace is required");
            fragmentId = Objects.requireNonNull(fragmentId, "fragmentId is required");
            resourcePath = Objects.requireNonNull(resourcePath, "resourcePath is required");
            documentOrdinal = Objects.requireNonNull(documentOrdinal, "documentOrdinal is required");
            representation = Objects.requireNonNull(representation, "representation is required");
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
    public sealed interface EvidenceSourceIdentityPayload permits EvidenceSourceFollowUpIdentity {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EvidenceSourceFollowUpIdentity(String kind, Optional<MapperStatementIdentityPayload> statementIdentity,
                                                 Optional<MapperFragmentIdentityPayload> fragmentIdentity)
            implements FollowUpIdentity, EvidenceSourceIdentityPayload {
        public EvidenceSourceFollowUpIdentity {
            kind = Objects.requireNonNull(kind, "kind is required");
            statementIdentity = Optional.ofNullable(statementIdentity).orElse(Optional.empty());
            fragmentIdentity = Optional.ofNullable(fragmentIdentity).orElse(Optional.empty());
            boolean statementKind = "ANNOTATION_SQL".equals(kind) || "MAPPER_STATEMENT".equals(kind);
            boolean fragmentKind = "MAPPER_FRAGMENT".equals(kind);
            if (!statementKind && !fragmentKind) {
                throw new IllegalArgumentException("unsupported evidence identity kind");
            }
            if (statementKind != statementIdentity.isPresent() || fragmentKind != fragmentIdentity.isPresent()) {
                throw new IllegalArgumentException("evidence identity does not match kind");
            }
        }
    }

    private static String requiredKind(String value, String expected) {
        String required = Objects.requireNonNull(value, "kind is required");
        if (!expected.equals(required)) {
            throw new IllegalArgumentException("unexpected discriminator");
        }
        return required;
    }

    private static <T> Optional<T> optional(Optional<T> value) {
        return Optional.ofNullable(value).orElse(Optional.empty());
    }

    public record ApiErrorResponse(String errorCode, String message, String repoId, String expectedRevision,
                                   String currentRevision, MethodTarget target, List<MethodTarget> candidates,
                                   String requestId) {
    }
}
