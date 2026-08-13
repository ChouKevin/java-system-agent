package com.java.system.agent.codeintelligence.semantic.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

    public record EntryPointClassResponse(SourceTypeIdentityPayload sourceType, String description, List<String> basePaths,
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
                                                 List<MethodTarget> candidates, String reasonCode,
                                                 List<AvailableFollowUp> availableFollowUps) {
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

    public record Position(
            @JsonPropertyDescription("Zero-based source line number") @NotNull @Min(0) Integer line,
            @JsonPropertyDescription("Zero-based UTF-16 code-unit character offset within line") @NotNull @Min(0) Integer character) {
        public Position {
            line = Objects.requireNonNull(line, "line is required");
            character = Objects.requireNonNull(character, "character is required");
        }
    }

    /** Java 型別的 HTTP 識別資料 */
    public record JavaTypeIdentityPayload(
            @JsonPropertyDescription("Java package only, for example com.example.payment") @NotNull String packageName,
            @JsonPropertyDescription("Canonical class name relative to package; nested types use dots") @NotBlank String className) {

        public JavaTypeIdentityPayload {
            packageName = Objects.requireNonNull(packageName, "packageName is required");
            className = javaQualifiedIdentifier(className, true, "className");
        }
    }

    /** 以來源檔案限定的 Java 型別 HTTP 識別資料 */
    public record SourceTypeIdentityPayload(
            @JsonPropertyDescription("Declaring Java package and class identity") @NotNull @Valid JavaTypeIdentityPayload javaType,
            @JsonPropertyDescription("Repository-relative Java source file path") @NotBlank String sourceFile)
            implements InternalReferenceIdentity {

        public SourceTypeIdentityPayload {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
            sourceFile = repositoryRelativePath(sourceFile, "sourceFile");
        }
    }

    /** 正規方法目標 HTTP 資料 */
    public record MethodTargetPayload(
            @JsonPropertyDescription("Source-bound declaring type") @NotNull @Valid SourceTypeIdentityPayload sourceType,
            @JsonPropertyDescription("Declared Java method name") @NotBlank String methodName,
            @JsonPropertyDescription("Canonical parameter type names in declaration order; [] means zero arguments")
            @NotNull List<@NotBlank String> parameterTypes) implements FollowUpTarget, InternalReferenceIdentity {

        public MethodTargetPayload {
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
            methodName = javaQualifiedIdentifier(methodName, false, "methodName");
            parameterTypes = List.copyOf(Objects.requireNonNull(
                    parameterTypes, "parameterTypes are required"));
            for (String parameterType : parameterTypes) {
                requiredText(parameterType, "parameterType");
            }
        }
    }

    /** 零基 UTF-16 半開文字範圍 HTTP 資料 */
    public record TextRangePayload(
            @JsonPropertyDescription("Inclusive zero-based UTF-16 start position") @NotNull @Valid Position start,
            @JsonPropertyDescription("Exclusive zero-based UTF-16 end position; ranges are half-open") @NotNull @Valid Position end) {

        public TextRangePayload {
            start = Objects.requireNonNull(start, "start is required");
            end = Objects.requireNonNull(end, "end is required");
            if (comparePositions(start, end) > 0) {
                throw new IllegalArgumentException("half-open range end must not precede start");
            }
        }
    }

    /** 型別直接成員或方法範圍成員的封閉 identity */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "scope", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = SourceMemberIdentityPayload.TypeMember.class, name = "TYPE"),
            @JsonSubTypes.Type(value = SourceMemberIdentityPayload.MethodScoped.class, name = "METHOD")})
    public sealed interface SourceMemberIdentityPayload extends InternalReferenceIdentity permits SourceMemberIdentityPayload.TypeMember,
            SourceMemberIdentityPayload.MethodScoped {

        record TypeMember(String scope, @NotNull @Valid SourceTypeIdentityPayload ownerType, @NotBlank String name)
                implements SourceMemberIdentityPayload, ConceptIdentityTargetPayload {
            public TypeMember {
                scope = requiredKind(scope, "TYPE");
                ownerType = Objects.requireNonNull(ownerType, "ownerType is required");
                name = javaQualifiedIdentifier(name, false, "name");
            }
        }

        record MethodScoped(String scope, @NotNull @Valid MethodTargetPayload declaringMethod,
                            @NotNull @Valid TextRangePayload declarationRange, @NotBlank String name)
                implements SourceMemberIdentityPayload, ConceptIdentityTargetPayload {
            public MethodScoped {
                scope = requiredKind(scope, "METHOD");
                declaringMethod = Objects.requireNonNull(declaringMethod, "declaringMethod is required");
                declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
                name = javaQualifiedIdentifier(name, false, "name");
            }
        }
    }

    /** 含有來源檔案的可導覽文字範圍 HTTP 資料 */
    public record SourceRangePayload(
            @JsonPropertyDescription("Repository-relative source file path") @NotBlank String sourceFile,
            @JsonPropertyDescription("Zero-based UTF-16 half-open range in sourceFile") @NotNull @Valid TextRangePayload range) {

        public SourceRangePayload {
            sourceFile = repositoryRelativePath(sourceFile, "sourceFile");
            range = Objects.requireNonNull(range, "range is required");
        }
    }

    /** Java Semantic Service 回傳的具型別 follow-up HTTP contract data，供 Agent runtime 建立後續查詢 capability */
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
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
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
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ConceptFollowUpIdentity(
                                          @JsonPropertyDescription("One of TYPE, METHOD, FIELD, ANNOTATION_USAGE, TYPE_USAGE, API_ROUTE, MQ_DESTINATION, SCHEDULE, MAPPER_STATEMENT, MAPPER_STATEMENT_VARIANT") String kind,
                                          @JsonPropertyDescription("Required only for TYPE") Optional<SourceTypeIdentityPayload> sourceType,
                                          @JsonPropertyDescription("Required only for METHOD, API_ROUTE, MQ_DESTINATION, and SCHEDULE") Optional<MethodTargetPayload> target,
                                          @JsonPropertyDescription("Required only for FIELD, MAPPER_STATEMENT, and MAPPER_STATEMENT_VARIANT") Optional<ConceptIdentityTargetPayload> identity,
                                          @JsonPropertyDescription("Required only for ANNOTATION_USAGE") Optional<DeclarationSubjectPayload> declaration,
                                          @JsonPropertyDescription("Required only for ANNOTATION_USAGE") Optional<AnnotationTypePayload> annotationType,
                                          @JsonPropertyDescription("Required only for TYPE_USAGE") Optional<DeclarationSubjectPayload> owner,
                                          @JsonPropertyDescription("Required only for TYPE_USAGE") Optional<TypeUsageLocationPayload> location,
                                          @JsonPropertyDescription("Required only for TYPE_USAGE") Optional<List<TypeUsagePathPayload>> path,
                                          @JsonPropertyDescription("Required only for TYPE_USAGE") Optional<ReferencedTypePayload> referencedType,
                                          @JsonPropertyDescription("Required only for API_ROUTE") Optional<String> httpVerb,
                                          @JsonPropertyDescription("Required only for API_ROUTE") Optional<String> route,
                                          @JsonPropertyDescription("Required only for MQ_DESTINATION") Optional<String> broker,
                                          @JsonPropertyDescription("Required only for MQ_DESTINATION") Optional<String> destination,
                                          @JsonPropertyDescription("Required only for SCHEDULE") Optional<String> triggerKind,
                                          @JsonPropertyDescription("Optional only for SCHEDULE") Optional<String> triggerValue)
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
            validateConceptIdentity(kind, sourceType, target, identity, declaration, annotationType, owner, location,
                    path, referencedType, httpVerb, route, broker, destination, triggerKind, triggerValue);
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
    public record SourceSymbolContextPayload(
            @JsonPropertyDescription("Java type that contains the symbol") @NotNull @Valid JavaTypeIdentityPayload javaType,
            @JsonPropertyDescription("Optional repository-relative source file path") Optional<String> sourceFile,
            @JsonPropertyDescription("Optional declaring method context for disambiguation")
            Optional<@Valid SourceSymbolMethodContextPayload> method) {
        public SourceSymbolContextPayload {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
            sourceFile = Optional.ofNullable(sourceFile).orElse(Optional.empty());
            sourceFile = sourceFile.map(value -> repositoryRelativePath(value, "sourceFile"));
            method = Optional.ofNullable(method).orElse(Optional.empty());
        }
    }

    /** 來源符號的可選方法 context */
    public record SourceSymbolMethodContextPayload(@NotBlank String name, @NotNull List<@NotBlank String> parameterTypes) {
        public SourceSymbolMethodContextPayload {
            name = javaQualifiedIdentifier(name, false, "name");
            parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes are required"));
            for (String parameterType : parameterTypes) {
                requiredText(parameterType, "parameterType");
            }
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

    public record TypeDeclarationSubjectPayload(String kind, @NotNull @Valid SourceTypeIdentityPayload sourceType)
            implements DeclarationSubjectPayload {
        public TypeDeclarationSubjectPayload {
            kind = requiredKind(kind, "TYPE");
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
        }
    }

    public record ResolvedMethodDeclarationSubjectPayload(String kind, @NotNull @Valid MethodTargetPayload target)
            implements DeclarationSubjectPayload {
        public ResolvedMethodDeclarationSubjectPayload {
            kind = requiredKind(kind, "METHOD");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    public record UnresolvedMethodDeclarationSubjectPayload(String kind, @NotNull @Valid MethodTargetPayload target)
            implements DeclarationSubjectPayload {
        public UnresolvedMethodDeclarationSubjectPayload {
            kind = requiredKind(kind, "METHOD_UNRESOLVED");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    public record FieldDeclarationSubjectPayload(String kind, @NotNull @Valid SourceMemberIdentityPayload identity)
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

    public record ResolvedAnnotationTypePayload(String status, @NotNull @Valid JavaTypeIdentityPayload javaType)
            implements AnnotationTypePayload {
        public ResolvedAnnotationTypePayload {
            status = requiredKind(status, "RESOLVED");
            javaType = Objects.requireNonNull(javaType, "javaType is required");
        }
    }

    public record UnresolvedAnnotationTypePayload(String status, @NotBlank String writtenName)
            implements AnnotationTypePayload {
        public UnresolvedAnnotationTypePayload {
            status = requiredKind(status, "UNRESOLVED");
            writtenName = Objects.requireNonNull(writtenName, "writtenName is required");
        }
    }

    public record TypeUsageLocationPayload(@NotBlank String slot, @NotNull @Min(0) Integer index) {
        public TypeUsageLocationPayload {
            slot = requiredText(slot, "slot");
            index = Objects.requireNonNull(index, "index is required");
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
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

    public record TypeArgumentPathPayload(String kind, @NotNull @Min(0) Integer index) implements TypeUsagePathPayload {
        public TypeArgumentPathPayload {
            kind = requiredKind(kind, "TYPE_ARGUMENT");
            index = Objects.requireNonNull(index, "index is required");
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
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

    public record TypeVariableBoundPathPayload(String kind, @NotNull @Min(0) Integer index) implements TypeUsagePathPayload {
        public TypeVariableBoundPathPayload {
            kind = requiredKind(kind, "TYPE_VARIABLE_BOUND");
            index = Objects.requireNonNull(index, "index is required");
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
        }
    }

    public record ReferencedTypePayload(@NotNull @Valid JavaTypeIdentityPayload javaType,
                                        @NotNull @Min(0) Integer arrayDimensions) {
        public ReferencedTypePayload {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
            arrayDimensions = Objects.requireNonNull(arrayDimensions, "arrayDimensions is required");
            if (arrayDimensions < 0) {
                throw new IllegalArgumentException("arrayDimensions must not be negative");
            }
        }
    }

    public record MapperStatementKeyPayload(@NotBlank String namespace, @NotBlank String statementId)
            implements ConceptIdentityTargetPayload {
        public MapperStatementKeyPayload {
            namespace = requiredText(namespace, "namespace");
            statementId = requiredText(statementId, "statementId");
        }
    }

    public enum MapperStatementRepresentation {
        MAPPER_XML_ELEMENT,
        ANNOTATION_SQL_TEXT
    }

    public enum MapperFragmentRepresentation {
        MAPPER_XML_ELEMENT
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
    public record MapperStatementIdentityPayload(@NotNull @Valid MapperStatementKeyPayload statementKey, @NotBlank String resourcePath,
                                                 Optional<@NotBlank String> databaseId, @NotNull @Min(0) Integer documentOrdinal,
                                                 @NotNull MapperStatementRepresentation representation)
            implements ConceptIdentityTargetPayload {
        public MapperStatementIdentityPayload {
            statementKey = Objects.requireNonNull(statementKey, "statementKey is required");
            resourcePath = repositoryRelativePath(resourcePath, "resourcePath");
            databaseId = Optional.ofNullable(databaseId).orElse(Optional.empty());
            databaseId = databaseId.map(value -> requiredText(value, "databaseId"));
            documentOrdinal = Objects.requireNonNull(documentOrdinal, "documentOrdinal is required");
            if (documentOrdinal < 0) {
                throw new IllegalArgumentException("documentOrdinal must not be negative");
            }
            representation = Objects.requireNonNull(representation, "representation is required");
        }
    }

    public record MapperFragmentIdentityPayload(@NotBlank String namespace, @NotBlank String fragmentId, @NotBlank String resourcePath,
                                                @NotNull @Min(0) Integer documentOrdinal,
                                                @NotNull MapperFragmentRepresentation representation) {
        public MapperFragmentIdentityPayload {
            namespace = requiredText(namespace, "namespace");
            fragmentId = requiredText(fragmentId, "fragmentId");
            resourcePath = repositoryRelativePath(resourcePath, "resourcePath");
            documentOrdinal = Objects.requireNonNull(documentOrdinal, "documentOrdinal is required");
            if (documentOrdinal < 0) {
                throw new IllegalArgumentException("documentOrdinal must not be negative");
            }
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
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record EvidenceSourceFollowUpIdentity(
                                                 @JsonPropertyDescription("One of ANNOTATION_SQL, MAPPER_STATEMENT, MAPPER_FRAGMENT") String kind,
                                                 @JsonPropertyDescription("Required for ANNOTATION_SQL and MAPPER_STATEMENT only") Optional<MapperStatementIdentityPayload> statementIdentity,
                                                 @JsonPropertyDescription("Required for MAPPER_FRAGMENT only") Optional<MapperFragmentIdentityPayload> fragmentIdentity)
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

    private static void validateConceptIdentity(
            String kind, Optional<?> sourceType, Optional<?> target, Optional<?> identity,
            Optional<?> declaration, Optional<?> annotationType, Optional<?> owner, Optional<?> location,
            Optional<?> path, Optional<?> referencedType, Optional<?> httpVerb, Optional<?> route,
            Optional<?> broker, Optional<?> destination, Optional<?> triggerKind, Optional<?> triggerValue) {
        List<Optional<?>> all = List.of(sourceType, target, identity, declaration, annotationType, owner, location,
                path, referencedType, httpVerb, route, broker, destination, triggerKind, triggerValue);
        switch (kind) {
            case "TYPE" -> exactConceptIdentityFields(all, Set.of(0), Set.of());
            case "METHOD" -> exactConceptIdentityFields(all, Set.of(1), Set.of());
            case "FIELD" -> {
                exactConceptIdentityFields(all, Set.of(2), Set.of());
                if (!(identity.orElseThrow() instanceof SourceMemberIdentityPayload)) {
                    throw new IllegalArgumentException("concept FIELD identity subtype does not match kind");
                }
            }
            case "ANNOTATION_USAGE" -> exactConceptIdentityFields(all, Set.of(3, 4), Set.of());
            case "TYPE_USAGE" -> exactConceptIdentityFields(all, Set.of(5, 6, 7, 8), Set.of());
            case "API_ROUTE" -> exactConceptIdentityFields(all, Set.of(1, 9, 10), Set.of());
            case "MQ_DESTINATION" -> exactConceptIdentityFields(all, Set.of(1, 11, 12), Set.of());
            case "SCHEDULE" -> exactConceptIdentityFields(all, Set.of(1, 13), Set.of(14));
            case "MAPPER_STATEMENT" -> {
                exactConceptIdentityFields(all, Set.of(2), Set.of());
                requiredIdentityType(identity, MapperStatementKeyPayload.class);
            }
            case "MAPPER_STATEMENT_VARIANT" -> {
                exactConceptIdentityFields(all, Set.of(2), Set.of());
                requiredIdentityType(identity, MapperStatementIdentityPayload.class);
            }
            default -> throw new IllegalArgumentException("unsupported concept identity kind");
        }
    }

    private static void exactConceptIdentityFields(
            List<Optional<?>> all, Set<Integer> requiredPositions, Set<Integer> optionalPositions) {
        for (int index = 0; index < all.size(); index++) {
            boolean present = all.get(index).isPresent();
            if ((requiredPositions.contains(index) && !present)
                    || (!requiredPositions.contains(index) && !optionalPositions.contains(index) && present)) {
                throw new IllegalArgumentException("concept identity does not match kind");
            }
        }
    }

    private static void requiredIdentityType(Optional<?> identity, Class<?> expectedType) {
        if (!expectedType.isInstance(identity.orElseThrow())) {
            throw new IllegalArgumentException("concept identity subtype does not match kind");
        }
    }

    private static int comparePositions(Position start, Position end) {
        int lineComparison = Integer.compare(start.line(), end.line());
        if (lineComparison != 0) {
            return lineComparison;
        }
        return Integer.compare(start.character(), end.character());
    }

    private static <T> Optional<T> optional(Optional<T> value) {
        return Optional.ofNullable(value).orElse(Optional.empty());
    }

    private static String requiredText(String value, String name) {
        String required = Objects.requireNonNull(value, name + " is required");
        if (required.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return required;
    }

    public static String repositoryRelativePath(String value, String name) {
        String path = requiredText(value, name);
        if (path.length() > 1_024 || hasControlCharacter(path) || endsWithTerminalWhitespace(path)
                || path.startsWith("/") || path.startsWith("\\") || path.contains("\\")) {
            throw new IllegalArgumentException(name + " must be a normalized repository-relative path");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(name + " must be a normalized repository-relative path");
            }
        }
        return path;
    }

    public static String javaQualifiedIdentifier(String value, boolean allowsDots, String name) {
        String required = requiredText(value, name);
        if (required.length() > 255 || (!allowsDots && required.indexOf('.') >= 0)) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        String[] parts = allowsDots ? required.split("\\.", -1) : new String[]{required};
        for (String part : parts) {
            if (part.isEmpty() || !isJavaIdentifier(part)) {
                throw new IllegalArgumentException(name + " has an invalid format");
            }
        }
        return required;
    }

    private static boolean hasControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || (character >= 0x7f && character <= 0x9f)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithTerminalWhitespace(String value) {
        int codePoint = value.codePointBefore(value.length());
        return Character.isWhitespace(codePoint) || codePoint == 0x00a0 || codePoint == 0x1680
                || codePoint == 0x2007 || codePoint == 0x202f;
    }

    private static boolean isJavaIdentifier(String value) {
        int first = value.codePointAt(0);
        if (!(Character.isLetter(first) || Character.getType(first) == Character.LETTER_NUMBER
                || Character.getType(first) == Character.CURRENCY_SYMBOL
                || Character.getType(first) == Character.CONNECTOR_PUNCTUATION)) {
            return false;
        }
        for (int index = Character.charCount(first); index < value.length();) {
            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            if (!(Character.isLetter(codePoint) || type == Character.LETTER_NUMBER || type == Character.CURRENCY_SYMBOL
                    || type == Character.CONNECTOR_PUNCTUATION || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK || Character.isDigit(codePoint))) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    /** 結構化探索的固定頁面計數 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PageResponse(int offset, int limit, int returnedCount, long totalCount, boolean hasMore) {
    }

    /** 結構化探索對來源快照的覆蓋摘要 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConceptCoverageResponse(String status, int scannedFileCount, int extractedFileCount,
                                          int syntaxFailedFileCount) {
    }

    /** 有界結果集合的計數與截斷狀態 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BoundedResultResponse(int limit, int returnedCount, int totalCount, boolean truncated) {
    }

    /** 概念候選的最小投影及其後續操作 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConceptCandidateResponse(ConceptFollowUpIdentity identity, String displayValue,
                                           List<String> matchedTerms, String authority,
                                           Optional<ConceptCandidateDetailsResponse> details,
                                           List<ConceptEvidenceResponse> evidence,
                                           List<AvailableFollowUp> availableFollowUps) {
        public ConceptCandidateResponse {
            identity = Objects.requireNonNull(identity, "concept identity is required");
            matchedTerms = List.copyOf(Objects.requireNonNull(matchedTerms, "matched terms are required"));
            details = optional(details);
            evidence = List.copyOf(Objects.requireNonNull(evidence, "concept evidence is required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "concept follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConceptEvidenceResponse(ConceptFollowUpIdentity identity) {
        public ConceptEvidenceResponse {
            identity = Objects.requireNonNull(identity, "concept evidence identity is required");
        }
    }

    /** 概念候選的額外封閉細節 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = FieldConceptCandidateDetailsResponse.class, name = "FIELD"),
            @JsonSubTypes.Type(value = MapperStatementConceptCandidateDetailsResponse.class, name = "MAPPER_STATEMENT")})
    public sealed interface ConceptCandidateDetailsResponse permits FieldConceptCandidateDetailsResponse,
            MapperStatementConceptCandidateDetailsResponse {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FieldConceptCandidateDetailsResponse(String kind, FieldTypeReferenceResponse declaredType)
            implements ConceptCandidateDetailsResponse {
        public FieldConceptCandidateDetailsResponse {
            declaredType = Objects.requireNonNull(declaredType, "field concept declared type is required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MapperStatementConceptCandidateDetailsResponse(String kind, MapperStatementMappingResponse mapping)
            implements ConceptCandidateDetailsResponse {
        public MapperStatementConceptCandidateDetailsResponse {
            mapping = Objects.requireNonNull(mapping, "mapper statement mapping is required");
        }
    }

    /** mapper statement 到 Java 方法的封閉 mapping 結果 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = ResolvedMapperStatementMappingResponse.class, name = "RESOLVED"),
            @JsonSubTypes.Type(value = AmbiguousMapperStatementMappingResponse.class, name = "AMBIGUOUS"),
            @JsonSubTypes.Type(value = UnresolvedMapperStatementMappingResponse.class, name = "UNRESOLVED")})
    public sealed interface MapperStatementMappingResponse permits ResolvedMapperStatementMappingResponse,
            AmbiguousMapperStatementMappingResponse, UnresolvedMapperStatementMappingResponse {
        MapperStatementKeyPayload statement();

        String status();

        List<MapperSourceMethodCandidateResponse> candidates();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ResolvedMapperStatementMappingResponse(MapperStatementKeyPayload statement, String status,
                                                         List<MapperSourceMethodCandidateResponse> candidates)
            implements MapperStatementMappingResponse {
        public ResolvedMapperStatementMappingResponse {
            statement = Objects.requireNonNull(statement, "resolved mapper statement is required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "resolved mapper candidates are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AmbiguousMapperStatementMappingResponse(MapperStatementKeyPayload statement, String status,
                                                          List<MapperSourceMethodCandidateResponse> candidates)
            implements MapperStatementMappingResponse {
        public AmbiguousMapperStatementMappingResponse {
            statement = Objects.requireNonNull(statement, "ambiguous mapper statement is required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "ambiguous mapper candidates are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UnresolvedMapperStatementMappingResponse(MapperStatementKeyPayload statement, String status,
                                                           String reason,
                                                           List<MapperSourceMethodCandidateResponse> candidates)
            implements MapperStatementMappingResponse {
        public UnresolvedMapperStatementMappingResponse {
            statement = Objects.requireNonNull(statement, "unresolved mapper statement is required");
            reason = Objects.requireNonNull(reason, "unresolved mapper reason is required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "unresolved mapper candidates are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MapperSourceMethodCandidateResponse(MethodTargetPayload target,
                                                      List<AvailableFollowUp> availableFollowUps) {
        public MapperSourceMethodCandidateResponse {
            target = Objects.requireNonNull(target, "mapper source method target is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "mapper source method follow-ups are required"));
        }
    }

    /** 欄位宣告型別的遞迴封閉證據 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = NamedFieldTypeReferenceResponse.class, name = "NAMED"),
            @JsonSubTypes.Type(value = ParameterizedFieldTypeReferenceResponse.class, name = "PARAMETERIZED"),
            @JsonSubTypes.Type(value = PrimitiveFieldTypeReferenceResponse.class, name = "PRIMITIVE"),
            @JsonSubTypes.Type(value = ArrayFieldTypeReferenceResponse.class, name = "ARRAY"),
            @JsonSubTypes.Type(value = WildcardFieldTypeReferenceResponse.class, name = "WILDCARD"),
            @JsonSubTypes.Type(value = TypeVariableFieldTypeReferenceResponse.class, name = "TYPE_VARIABLE")})
    public sealed interface FieldTypeReferenceResponse permits NamedFieldTypeReferenceResponse,
            ParameterizedFieldTypeReferenceResponse, PrimitiveFieldTypeReferenceResponse,
            ArrayFieldTypeReferenceResponse, WildcardFieldTypeReferenceResponse,
            TypeVariableFieldTypeReferenceResponse {
        String kind();

        String writtenType();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NamedFieldTypeReferenceResponse(String kind, String writtenType, String simpleTypeName,
                                                  Optional<JavaTypeIdentityPayload> resolvedJavaType,
                                                  boolean sourceDefined) implements FieldTypeReferenceResponse {
        public NamedFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "named written type is required");
            simpleTypeName = Objects.requireNonNull(simpleTypeName, "named simple type name is required");
            resolvedJavaType = optional(resolvedJavaType);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ParameterizedFieldTypeReferenceResponse(String kind, String writtenType,
                                                          NamedFieldTypeReferenceResponse rawType,
                                                          List<FieldTypeReferenceResponse> typeArguments)
            implements FieldTypeReferenceResponse {
        public ParameterizedFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "parameterized written type is required");
            rawType = Objects.requireNonNull(rawType, "parameterized raw type is required");
            typeArguments = List.copyOf(Objects.requireNonNull(typeArguments,
                    "parameterized type arguments are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PrimitiveFieldTypeReferenceResponse(String kind, String writtenType) implements FieldTypeReferenceResponse {
        public PrimitiveFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "primitive written type is required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ArrayFieldTypeReferenceResponse(String kind, String writtenType, FieldTypeReferenceResponse elementType,
                                                  int dimensions) implements FieldTypeReferenceResponse {
        public ArrayFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "array written type is required");
            elementType = Objects.requireNonNull(elementType, "array element type is required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record WildcardFieldTypeReferenceResponse(String kind, String writtenType,
                                                     Optional<FieldTypeReferenceResponse> upperBound,
                                                     Optional<FieldTypeReferenceResponse> lowerBound,
                                                     boolean sourceDefined) implements FieldTypeReferenceResponse {
        public WildcardFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "wildcard written type is required");
            upperBound = optional(upperBound);
            lowerBound = optional(lowerBound);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TypeVariableFieldTypeReferenceResponse(String kind, String writtenType, String variableName,
                                                         List<FieldTypeReferenceResponse> upperBounds,
                                                         boolean sourceDefined) implements FieldTypeReferenceResponse {
        public TypeVariableFieldTypeReferenceResponse {
            writtenType = Objects.requireNonNull(writtenType, "type variable written type is required");
            variableName = Objects.requireNonNull(variableName, "type variable name is required");
            upperBounds = List.copyOf(Objects.requireNonNull(upperBounds, "type variable bounds are required"));
        }
    }

    /** 結構化概念探索的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiscoverConceptsResponse(String repoId, String analyzedRevision, List<String> normalizedTerms,
                                           List<String> searchedKinds, List<String> supportedKinds,
                                           List<String> limitations, List<ConceptCandidateResponse> candidates,
                                           PageResponse page, ConceptCoverageResponse coverage,
                                           List<IssueSummaryResponse> issueSummaries,
                                           List<AvailableFollowUp> availableFollowUps,
                                           List<UnavailableFollowUpResponse> unavailableFollowUps) {
        public DiscoverConceptsResponse {
            normalizedTerms = List.copyOf(Objects.requireNonNull(normalizedTerms, "normalized terms are required"));
            searchedKinds = List.copyOf(Objects.requireNonNull(searchedKinds, "searched kinds are required"));
            supportedKinds = List.copyOf(Objects.requireNonNull(supportedKinds, "supported kinds are required"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations are required"));
            candidates = List.copyOf(Objects.requireNonNull(candidates, "concept candidates are required"));
            page = Objects.requireNonNull(page, "concept page is required");
            coverage = Objects.requireNonNull(coverage, "concept coverage is required");
            issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries, "concept issues are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "concept response follow-ups are required"));
            unavailableFollowUps = List.copyOf(Objects.requireNonNull(unavailableFollowUps,
                    "unavailable concept follow-ups are required"));
        }
    }

    /** 概念 resolve 的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ResolveConceptResponse(String repoId, String analyzedRevision, ConceptCandidateResponse candidate) {
        public ResolveConceptResponse {
            candidate = Objects.requireNonNull(candidate, "resolved concept candidate is required");
        }
    }

    /** 事件監聽器候選及其精確 target */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EventListenerCandidateResponse(MethodTargetPayload target, List<ListenerAnnotationEvidenceResponse> listenerAnnotations,
                                                 TextRangePayload sourceRange,
                                                 List<AvailableFollowUp> availableFollowUps) {
        public EventListenerCandidateResponse {
            target = Objects.requireNonNull(target, "listener target is required");
            listenerAnnotations = List.copyOf(Objects.requireNonNull(listenerAnnotations,
                    "listener annotations are required"));
            sourceRange = Objects.requireNonNull(sourceRange, "listener source range is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "listener follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ListenerAnnotationEvidenceResponse(String kind, String matchKind) {
    }

    /** 事件監聽器的封閉 issue 摘要 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ListenerObservationSummaryResponse(String code, long totalCount, List<SourceRangePayload> samples) {
        public ListenerObservationSummaryResponse {
            samples = List.copyOf(Objects.requireNonNull(samples, "listener observation samples are required"));
        }
    }

    /** 事件監聽器探索的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiscoverEventListenersResponse(String repoId, String analyzedRevision, String requestedEventType,
                                                 List<EventListenerCandidateResponse> candidates, PageResponse page,
                                                 List<ListenerObservationSummaryResponse> observationSummaries,
                                                 List<AvailableFollowUp> availableFollowUps) {
        public DiscoverEventListenersResponse {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "listener candidates are required"));
            page = Objects.requireNonNull(page, "listener page is required");
            observationSummaries = List.copyOf(Objects.requireNonNull(observationSummaries,
                    "listener observations are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "listener response follow-ups are required"));
        }
    }

    /** 方法實作候選及其後續操作 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MethodImplementationCandidateResponse(MethodTargetPayload target, boolean primary, List<String> qualifiers,
                                                        List<String> profiles,
                                                        List<AvailableFollowUp> availableFollowUps) {
        public MethodImplementationCandidateResponse {
            target = Objects.requireNonNull(target, "implementation target is required");
            qualifiers = List.copyOf(Objects.requireNonNull(qualifiers, "implementation qualifiers are required"));
            profiles = List.copyOf(Objects.requireNonNull(profiles, "implementation profiles are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "implementation follow-ups are required"));
        }
    }

    /** 方法實作探索的封閉 resolution */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MethodImplementationResolutionResponse(String status, List<IssueSummaryResponse> issueSummaries) {
        public MethodImplementationResolutionResponse {
            issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries,
                    "implementation issues are required"));
        }
    }

    /** 方法實作探索的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiscoverMethodImplementationsResponse(String repoId, String revision,
                                                        MethodTargetPayload requestedTarget,
                                                        List<MethodImplementationCandidateResponse> candidates,
                                                        BoundedResultResponse limits,
                                                        MethodImplementationResolutionResponse resolution) {
        public DiscoverMethodImplementationsResponse {
            requestedTarget = Objects.requireNonNull(requestedTarget, "requested method target is required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "implementation candidates are required"));
            limits = Objects.requireNonNull(limits, "implementation limits are required");
            resolution = Objects.requireNonNull(resolution, "implementation resolution is required");
        }
    }

    /** 型別成員回應的封閉變體 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = MethodTypeMemberResponse.class, name = "METHOD"),
            @JsonSubTypes.Type(value = FieldTypeMemberResponse.class, name = "FIELD"),
            @JsonSubTypes.Type(value = EnumConstantTypeMemberResponse.class, name = "ENUM_CONSTANT"),
            @JsonSubTypes.Type(value = RecordComponentTypeMemberResponse.class, name = "RECORD_COMPONENT")})
    public sealed interface TypeMemberResponse permits MethodTypeMemberResponse, FieldTypeMemberResponse,
            EnumConstantTypeMemberResponse, RecordComponentTypeMemberResponse {
        List<AvailableFollowUp> availableFollowUps();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MethodTypeMemberResponse(String kind, MethodTargetPayload target,
                                           List<AvailableFollowUp> availableFollowUps) implements TypeMemberResponse {
        public MethodTypeMemberResponse {
            target = Objects.requireNonNull(target, "member method target is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "member method follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FieldTypeMemberResponse(String kind, SourceMemberIdentityPayload identity, String writtenType,
                                          Optional<String> resolvedType, List<String> annotations, List<String> limitations,
                                          List<AvailableFollowUp> availableFollowUps) implements TypeMemberResponse {
        public FieldTypeMemberResponse {
            identity = Objects.requireNonNull(identity, "member field identity is required");
            writtenType = Objects.requireNonNull(writtenType, "member field written type is required");
            resolvedType = optional(resolvedType);
            annotations = List.copyOf(Objects.requireNonNull(annotations, "member field annotations are required"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "member field limitations are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "member field follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EnumConstantTypeMemberResponse(String kind, SourceMemberIdentityPayload identity,
                                                 TextRangePayload declarationRange, List<String> annotations,
                                                 List<AvailableFollowUp> availableFollowUps) implements TypeMemberResponse {
        public EnumConstantTypeMemberResponse {
            identity = Objects.requireNonNull(identity, "enum constant identity is required");
            declarationRange = Objects.requireNonNull(declarationRange, "enum constant declaration range is required");
            annotations = List.copyOf(Objects.requireNonNull(annotations, "enum constant annotations are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "enum constant follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RecordComponentTypeMemberResponse(String kind, SourceMemberIdentityPayload identity, String writtenType,
                                                    Optional<String> resolvedType, TextRangePayload declarationRange,
                                                    List<String> annotations, List<AvailableFollowUp> availableFollowUps)
            implements TypeMemberResponse {
        public RecordComponentTypeMemberResponse {
            identity = Objects.requireNonNull(identity, "record component identity is required");
            writtenType = Objects.requireNonNull(writtenType, "record component written type is required");
            resolvedType = optional(resolvedType);
            declarationRange = Objects.requireNonNull(declarationRange, "record component declaration range is required");
            annotations = List.copyOf(Objects.requireNonNull(annotations, "record component annotations are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "record component follow-ups are required"));
        }
    }

    /** 型別成員探索的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiscoverTypeMembersResponse(String repoId, String analyzedRevision, SourceTypeIdentityPayload sourceType,
                                              String typeKind, List<String> annotations, List<String> implementedTypes,
                                              List<String> extendedTypes,
                                              List<TypeMemberResponse> members, PageResponse page,
                                              ConceptCoverageResponse coverage,
                                              List<AvailableFollowUp> availableFollowUps) {
        public DiscoverTypeMembersResponse {
            sourceType = Objects.requireNonNull(sourceType, "member source type is required");
            typeKind = Objects.requireNonNull(typeKind, "type member kind is required");
            annotations = List.copyOf(Objects.requireNonNull(annotations, "type annotations are required"));
            implementedTypes = List.copyOf(Objects.requireNonNull(implementedTypes, "implemented types are required"));
            extendedTypes = List.copyOf(Objects.requireNonNull(extendedTypes, "extended types are required"));
            members = List.copyOf(Objects.requireNonNull(members, "type members are required"));
            page = Objects.requireNonNull(page, "member page is required");
            coverage = Objects.requireNonNull(coverage, "member coverage is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "member response follow-ups are required"));
        }
    }

    /** 可 materialize 的來源片段 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceSegmentPayload(SourceRangePayload location, String content,
                                       Optional<SourceRangePayload> nextLocation) {
        public SourceSegmentPayload {
            location = Objects.requireNonNull(location, "source segment location is required");
            content = Objects.requireNonNull(content, "source segment content is required");
            nextLocation = optional(nextLocation);
        }
    }

    /** 方法來源、evidence 與 range continuation 共用的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MethodSourceResponse(String repoId, String analyzedRevision, SourceRangePayload declarationLocation,
                                       SourceSegmentPayload segment, List<AvailableFollowUp> availableFollowUps) {
        public MethodSourceResponse {
            declarationLocation = Objects.requireNonNull(declarationLocation, "method declaration location is required");
            segment = Objects.requireNonNull(segment, "method source segment is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "method source follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceSegmentResponse(String repoId, String analyzedRevision, SourceSegmentPayload segment,
                                        boolean contextTruncated, List<AvailableFollowUp> availableFollowUps) {
        public SourceSegmentResponse {
            segment = Objects.requireNonNull(segment, "source segment is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "source segment follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EvidenceSourceResponse(String repoId, String analyzedRevision,
                                         EvidenceSourceFollowUpIdentity identity, SourceRangePayload location,
                                         SourceSegmentPayload segment, List<AvailableFollowUp> availableFollowUps) {
        public EvidenceSourceResponse {
            identity = Objects.requireNonNull(identity, "evidence identity is required");
            location = Objects.requireNonNull(location, "evidence location is required");
            segment = Objects.requireNonNull(segment, "evidence segment is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "evidence follow-ups are required"));
        }
    }

    /** source symbol context candidate 的精確 provider 變體 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = SourceTypeContextCandidateResponse.class, name = "SOURCE_TYPE"),
            @JsonSubTypes.Type(value = SourceMethodContextCandidateResponse.class, name = "METHOD")})
    public sealed interface SourceContextCandidateResponse permits SourceTypeContextCandidateResponse,
            SourceMethodContextCandidateResponse {
        AvailableFollowUp retry();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceTypeContextCandidateResponse(String kind, String sourceFile, AvailableFollowUp retry)
            implements SourceContextCandidateResponse {
        public SourceTypeContextCandidateResponse {
            sourceFile = Objects.requireNonNull(sourceFile, "source type context file is required");
            retry = Objects.requireNonNull(retry, "source type context retry is required");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceMethodContextCandidateResponse(String kind, MethodTargetPayload target, AvailableFollowUp retry)
            implements SourceContextCandidateResponse {
        public SourceMethodContextCandidateResponse {
            target = Objects.requireNonNull(target, "source method context target is required");
            retry = Objects.requireNonNull(retry, "source method context retry is required");
        }
    }

    /** source symbol candidate 的精確 provider 變體 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = VariableLikeSourceSymbolCandidateResponse.class, name = "FIELD"),
            @JsonSubTypes.Type(value = VariableLikeSourceSymbolCandidateResponse.class, name = "RECORD_COMPONENT"),
            @JsonSubTypes.Type(value = VariableLikeSourceSymbolCandidateResponse.class, name = "PARAMETER"),
            @JsonSubTypes.Type(value = VariableLikeSourceSymbolCandidateResponse.class, name = "LOCAL_VARIABLE"),
            @JsonSubTypes.Type(value = VariableLikeSourceSymbolCandidateResponse.class, name = "ENUM_CONSTANT"),
            @JsonSubTypes.Type(value = StaticConstantSourceSymbolCandidateResponse.class, name = "STATIC_CONSTANT"),
            @JsonSubTypes.Type(value = MethodSourceSymbolCandidateResponse.class, name = "METHOD"),
            @JsonSubTypes.Type(value = SourceTypeSymbolCandidateResponse.class, name = "SOURCE_TYPE")
    })
    public sealed interface SourceSymbolCandidateResponse permits VariableLikeSourceSymbolCandidateResponse,
            StaticConstantSourceSymbolCandidateResponse, MethodSourceSymbolCandidateResponse,
            SourceTypeSymbolCandidateResponse {
        String kind();

        TextRangePayload declarationRange();

        TextRangePayload representativeOccurrence();

        int occurrenceCount();

        List<AvailableFollowUp> availableFollowUps();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record VariableLikeSourceSymbolCandidateResponse(String kind, SourceMemberIdentityPayload identity,
                                                            DeclaredTypeResponse declaredType,
                                                            TextRangePayload declarationRange,
                                                            TextRangePayload representativeOccurrence,
                                                            int occurrenceCount,
                                                            List<AvailableFollowUp> availableFollowUps)
            implements SourceSymbolCandidateResponse {
        public VariableLikeSourceSymbolCandidateResponse {
            identity = Objects.requireNonNull(identity, "variable symbol identity is required");
            declaredType = Objects.requireNonNull(declaredType, "variable declared type is required");
            declarationRange = Objects.requireNonNull(declarationRange, "variable declaration range is required");
            representativeOccurrence = Objects.requireNonNull(representativeOccurrence,
                    "variable representative occurrence is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "variable symbol follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record StaticConstantSourceSymbolCandidateResponse(String kind, SourceMemberIdentityPayload identity,
                                                              DeclaredTypeResponse declaredType, String initializerSource,
                                                              TextRangePayload declarationRange,
                                                              TextRangePayload representativeOccurrence,
                                                              int occurrenceCount,
                                                              List<AvailableFollowUp> availableFollowUps)
            implements SourceSymbolCandidateResponse {
        public StaticConstantSourceSymbolCandidateResponse {
            identity = Objects.requireNonNull(identity, "constant symbol identity is required");
            declaredType = Objects.requireNonNull(declaredType, "constant declared type is required");
            initializerSource = Objects.requireNonNull(initializerSource, "constant initializer source is required");
            declarationRange = Objects.requireNonNull(declarationRange, "constant declaration range is required");
            representativeOccurrence = Objects.requireNonNull(representativeOccurrence,
                    "constant representative occurrence is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "constant symbol follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MethodSourceSymbolCandidateResponse(String kind, MethodTargetPayload target,
                                                      TextRangePayload declarationRange,
                                                      TextRangePayload representativeOccurrence, int occurrenceCount,
                                                      List<AvailableFollowUp> availableFollowUps)
            implements SourceSymbolCandidateResponse {
        public MethodSourceSymbolCandidateResponse {
            target = Objects.requireNonNull(target, "method symbol target is required");
            declarationRange = Objects.requireNonNull(declarationRange, "method declaration range is required");
            representativeOccurrence = Objects.requireNonNull(representativeOccurrence,
                    "method representative occurrence is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "method symbol follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceTypeSymbolCandidateResponse(String kind, SourceTypeIdentityPayload identity,
                                                    TextRangePayload declarationRange,
                                                    TextRangePayload representativeOccurrence, int occurrenceCount,
                                                    List<AvailableFollowUp> availableFollowUps)
            implements SourceSymbolCandidateResponse {
        public SourceTypeSymbolCandidateResponse {
            identity = Objects.requireNonNull(identity, "source type symbol identity is required");
            declarationRange = Objects.requireNonNull(declarationRange, "source type declaration range is required");
            representativeOccurrence = Objects.requireNonNull(representativeOccurrence,
                    "source type representative occurrence is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "source type symbol follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DeclaredTypeResponse(String writtenType, Optional<String> resolvedType) {
        public DeclaredTypeResponse {
            writtenType = Objects.requireNonNull(writtenType, "declared written type is required");
            resolvedType = optional(resolvedType);
        }
    }

    /** source symbol resolve 的成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ResolveSourceSymbolResponse(String repoId, String analyzedRevision, String status,
                                              List<SourceContextCandidateResponse> contextCandidates,
                                              BoundedResultResponse contextCandidateLimits,
                                              List<SourceSymbolCandidateResponse> candidates,
                                              List<SourceSymbolIssueSummaryResponse> issues) {
        public ResolveSourceSymbolResponse {
            contextCandidates = List.copyOf(Objects.requireNonNull(contextCandidates,
                    "source symbol context candidates are required"));
            contextCandidateLimits = Objects.requireNonNull(contextCandidateLimits,
                    "source symbol context candidate limits are required");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "source symbol candidates are required"));
            issues = List.copyOf(Objects.requireNonNull(issues, "source symbol issues are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceSymbolIssueSummaryResponse(String code, int count) {
    }

    /** internal reference 代表 occurrence */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReferenceOccurrenceResponse(TextRangePayload range, List<AvailableFollowUp> availableFollowUps) {
        public ReferenceOccurrenceResponse {
            range = Objects.requireNonNull(range, "reference range is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "reference occurrence follow-ups are required"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReferenceGroupResponse(InternalReferenceContextResponse context,
                                         List<ReferenceOccurrenceResponse> representativeReferences,
                                         BoundedResultResponse limits, List<AvailableFollowUp> availableFollowUps,
                                         List<UnavailableFollowUpResponse> unavailableFollowUps) {
        public ReferenceGroupResponse {
            context = Objects.requireNonNull(context, "reference group context is required");
            representativeReferences = List.copyOf(Objects.requireNonNull(representativeReferences,
                    "representative references are required"));
            limits = Objects.requireNonNull(limits, "reference limits are required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "reference group follow-ups are required"));
            unavailableFollowUps = List.copyOf(Objects.requireNonNull(unavailableFollowUps,
                    "unavailable reference group follow-ups are required"));
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = InternalReferenceTypeContextResponse.class, name = "TYPE"),
            @JsonSubTypes.Type(value = InternalReferenceMethodContextResponse.class, name = "METHOD")})
    public sealed interface InternalReferenceContextResponse permits InternalReferenceTypeContextResponse,
            InternalReferenceMethodContextResponse {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InternalReferenceTypeContextResponse(String kind, SourceTypeIdentityPayload sourceType)
            implements InternalReferenceContextResponse {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InternalReferenceMethodContextResponse(String kind, MethodTargetPayload method)
            implements InternalReferenceContextResponse {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InternalReferenceTargetDeclarationResponse(InternalReferenceFollowUpTarget target,
                                                             TextRangePayload declarationRange,
                                                             List<AvailableFollowUp> availableFollowUps) {
        public InternalReferenceTargetDeclarationResponse {
            target = Objects.requireNonNull(target, "reference declaration target is required");
            declarationRange = Objects.requireNonNull(declarationRange, "reference declaration range is required");
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "reference declaration follow-ups are required"));
        }
    }

    /** internal reference 成功回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FindInternalReferencesResponse(String repoId, String analyzedRevision, String status,
                                                 InternalReferenceTargetDeclarationResponse targetDeclaration,
                                                 int totalReferenceCount,
                                                 List<ReferenceGroupResponse> referenceGroups, PageResponse page,
                                                 List<IssueSummaryResponse> issueSummaries,
                                                 List<AvailableFollowUp> availableFollowUps) {
        public FindInternalReferencesResponse {
            targetDeclaration = Objects.requireNonNull(targetDeclaration, "reference target declaration is required");
            referenceGroups = List.copyOf(Objects.requireNonNull(referenceGroups,
                    "reference groups are required"));
            page = Objects.requireNonNull(page, "reference page is required");
            issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries, "reference issues are required"));
            availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps,
                    "reference response follow-ups are required"));
        }
    }

    /** provider 已封閉的 issue code/count */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record IssueSummaryResponse(String code, int count) {
    }

    /** provider 不可執行後續動作的封閉理由 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UnavailableFollowUpResponse(String reason, String recommendedAction) {
    }

    public record ApiErrorResponse(String errorCode, String message, String repoId, String expectedRevision,
                                   String currentRevision, MethodTarget target, List<MethodTarget> candidates,
                                   List<String> unavailableKinds, List<String> supportedKinds, String requestId) {
    }
}
