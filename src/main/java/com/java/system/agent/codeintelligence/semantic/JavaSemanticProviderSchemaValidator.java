package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 驗證 Java Semantic Service v1 回應在進入 answering 前符合 OpenAPI 文件
 */
final class JavaSemanticProviderSchemaValidator {

    private static final Pattern REPOSITORY_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern REVISION = Pattern.compile("(?:[0-9a-f]{40}|FIXTURE)");
    private static final Set<String> REPOSITORY_MODES = Set.of("REMOTE", "LOCAL_FIXTURE");
    private static final Set<String> RESOLUTION_STATUSES = Set.of("RESOLVED", "UNRESOLVED", "AMBIGUOUS");
    private static final Set<String> GRAPH_STATUSES = Set.of("SUCCESS", "PARTIAL");
    private static final Set<String> CONTENT_STATES = Set.of("FULL_SOURCE", "TARGET_ONLY", "EXTERNAL");
    private static final Set<String> TRAVERSAL_STATES = Set.of("EXPANDED", "DEPTH_BOUNDARY", "BUDGET_CUTOFF", "OPAQUE", "EXTERNAL");
    private static final Set<String> DISPATCH_KINDS = Set.of("SYNCHRONOUS", "ASYNC");
    private static final Set<String> LIMIT_REASONS = Set.of("NONE", "NODE_BUDGET");
    private static final Set<String> EDGE_STRATEGIES = Set.of("JDT_CALL_HIERARCHY", "JDT_DEFINITION_FALLBACK",
            "SPRING_BEAN_BY_QUALIFIER", "SPRING_BEAN_BY_PRIMARY", "SPRING_SINGLE_IMPLEMENTATION", "MYBATIS_MAPPER",
            "SPRING_DATA_REPOSITORY", "LOMBOK_GENERATED", "EXTERNAL_LIBRARY", "FEIGN_CLIENT",
            "BUSINESS_READ_FORBIDDEN", "SPRING_MULTIPLE_CANDIDATES", "DATA_ACCESS_WITHOUT_EVIDENCE");
    private static final Set<String> EDGE_CATEGORIES = Set.of("RESOLVED_ANALYZABLE", "RESOLVED_OPAQUE", "UNRESOLVED_GUESS");
    private static final Set<String> WARNING_CODES = Set.of("DESCENDANT_CALL_AMBIGUOUS", "DESCENDANT_CALL_UNRESOLVED",
            "INCOMING_CALLER_REJECTED", "NODE_BUDGET_REACHED");
    private static final Set<String> ERROR_CODES = Set.of("CHILD_SEMANTIC_QUERY_FAILED");
    private static final Set<String> API_MATCH_REASONS = Set.of("EXACT_NORMALIZED_PATH", "TEMPLATE_MATCH", "HTTP_METHOD_MATCH",
            "SHARED_STATIC_SEGMENT", "POSITIONAL_STATIC_SEGMENT", "SAME_SEGMENT_COUNT");
    private static final Set<String> API_ERROR_CODES = Set.of("REQUEST_INVALID", "SEMANTIC_UNAUTHORIZED",
            "REPOSITORY_NOT_FOUND", "REPOSITORY_NOT_READY", "REPOSITORY_REVISION_MISMATCH", "SEMANTIC_BINDING_AMBIGUOUS",
            "SEMANTIC_TARGET_NOT_FOUND", "SEMANTIC_BINDING_UNRESOLVED", "SEMANTIC_PROTOCOL_ERROR",
            "SEMANTIC_ENGINE_START_FAILED", "SEMANTIC_REQUEST_TIMEOUT", "INTERNAL_ERROR", "SEMANTIC_AUTH_DISABLED");

    List<SemanticDtos.RepositoryStatusResponse> repositories(List<SemanticDtos.RepositoryStatusResponse> responses) {
        List<SemanticDtos.RepositoryStatusResponse> validated = requiredList(responses, "repository response");
        for (SemanticDtos.RepositoryStatusResponse response : validated) {
            repository(response);
        }
        return validated;
    }

    SemanticDtos.RepositoryStatusResponse repository(SemanticDtos.RepositoryStatusResponse response) {
        SemanticDtos.RepositoryStatusResponse required = requiredObject(response, "repository status response");
        repositoryId(required.repoId(), "repository ID");
        enumValue(required.mode(), REPOSITORY_MODES, "repository mode");
        requiredString(required.displayName(), "repository display name");
        requiredBoolean(required.cloned(), "repository cloned");
        optionalRevision(required.currentRevision(), "repository current revision");
        return required;
    }

    SemanticDtos.EntryPointsResponse entryPoints(SemanticDtos.EntryPointsResponse response) {
        SemanticDtos.EntryPointsResponse required = requiredObject(response, "entry-points response");
        repositoryId(required.repoId(), "entry-points repository ID");
        revision(required.analyzedRevision(), "entry-points analyzed revision");
        for (SemanticDtos.EntryPointClassResponse entryPoint : requiredList(required.entryPoints(), "entry point")) {
            requiredString(entryPoint.className(), "entry point class name");
            requiredString(entryPoint.packageName(), "entry point package name");
            requiredString(entryPoint.packagePath(), "entry point package path");
            requiredString(entryPoint.description(), "entry point description");
            requiredStrings(entryPoint.basePaths(), "entry point base path");
            for (SemanticDtos.EntryPointMethodResponse method : requiredList(entryPoint.methods(), "entry point method")) {
                entryPointMethod(method);
            }
        }
        return required;
    }

    SemanticDtos.ApiRouteCandidatesResponse apiRoutes(SemanticDtos.ApiRouteCandidatesResponse response) {
        SemanticDtos.ApiRouteCandidatesResponse required = requiredObject(response, "API route candidates response");
        for (SemanticDtos.ApiRouteCandidateResponse candidate : requiredList(required.candidates(), "API route candidate")) {
            repositoryId(candidate.repoId(), "route repository ID");
            revision(candidate.analyzedRevision(), "route analyzed revision");
            requiredString(candidate.httpMethod(), "route HTTP method");
            requiredString(candidate.routeTemplate(), "route template");
            requiredString(candidate.packageName(), "route package name");
            requiredString(candidate.className(), "route class name");
            requiredString(candidate.methodName(), "route method name");
            resolution(candidate.analysisTarget());
            for (String reason : requiredStrings(candidate.matchReasons(), "route match reason")) {
                enumValue(reason, API_MATCH_REASONS, "route match reason");
            }
        }
        for (SemanticDtos.ApiRouteObservationResponse observation : requiredList(required.observations(), "API route observation")) {
            enumValue(observation.code(), Set.of("TRUNCATED_CANDIDATES"), "API route observation code");
            requiredString(observation.description(), "API route observation description");
        }
        return required;
    }

    void graph(String status, String analyzedRevision, String rootNodeId, SemanticDtos.GraphTraversal traversal,
               List<SemanticDtos.GraphNode> nodes, List<SemanticDtos.GraphEdge> edges,
               List<SemanticDtos.GraphWarning> warnings, List<SemanticDtos.GraphError> errors) {
        enumValue(status, GRAPH_STATUSES, "graph status");
        revision(analyzedRevision, "graph analyzed revision");
        requiredString(rootNodeId, "graph root node ID");
        graphTraversal(traversal);
        for (SemanticDtos.GraphNode node : requiredList(nodes, "graph node")) {
            graphNode(node);
        }
        for (SemanticDtos.GraphEdge edge : requiredList(edges, "graph edge")) {
            graphEdge(edge);
        }
        for (SemanticDtos.GraphWarning warning : requiredList(warnings, "graph warning")) {
            graphWarning(warning);
        }
        for (SemanticDtos.GraphError error : requiredList(errors, "graph error")) {
            graphError(error);
        }
    }

    void error(SemanticDtos.ApiErrorResponse response) {
        SemanticDtos.ApiErrorResponse required = requiredObject(response, "API error response");
        enumValue(required.errorCode(), API_ERROR_CODES, "API error code");
        requiredString(required.message(), "API error message");
        optionalRepositoryId(required.repoId(), "API error repository ID");
        optionalRevision(required.expectedRevision(), "API error expected revision");
        optionalRevision(required.currentRevision(), "API error current revision");
        if (Objects.nonNull(required.target())) {
            methodTarget(required.target());
        }
        requiredMethodTargets(required.candidates(), "API error candidate");
    }

    SemanticDtos.MethodTarget methodTarget(SemanticDtos.MethodTarget target) {
        SemanticDtos.MethodTarget required = requiredObject(target, "method target");
        sourceFile(required.sourceFile());
        requiredString(required.packageName(), "method target package");
        javaQualifiedIdentifier(required.className(), true, "method target class");
        javaQualifiedIdentifier(required.methodName(), false, "method target method");
        for (String parameterType : requiredStrings(required.parameterTypes(), "method target parameter type")) {
            nonblank(parameterType, "method target parameter type");
        }
        return required;
    }

    void repositoryId(String value, String description) {
        String required = requiredString(value, description);
        if (!REPOSITORY_ID.matcher(required).matches()) {
            throw contract(description + " has an invalid format");
        }
    }

    void revision(String value, String description) {
        String required = requiredString(value, description);
        if (!REVISION.matcher(required).matches()) {
            throw contract(description + " has an invalid format");
        }
    }

    private void entryPointMethod(SemanticDtos.EntryPointMethodResponse method) {
        SemanticDtos.EntryPointMethodResponse required = requiredObject(method, "entry point method");
        requiredString(required.name(), "entry point method name");
        requiredString(required.description(), "entry point method description");
        if (required instanceof SemanticDtos.ApiEntryPointMethodResponse api) {
            enumValue(api.type(), Set.of("API"), "API entry point type");
            requiredString(api.apiUrl(), "API entry point URL");
            requiredStrings(api.httpMethods(), "API entry point HTTP method");
            requiredStrings(api.swaggerDescriptions(), "API entry point Swagger description");
        } else if (required instanceof SemanticDtos.MqEntryPointMethodResponse mq) {
            enumValue(mq.type(), Set.of("MQ"), "MQ entry point type");
            enumValue(mq.broker(), Set.of("RABBIT", "KAFKA"), "MQ broker");
            requiredStrings(mq.destinations(), "MQ destination");
        } else if (required instanceof SemanticDtos.ScheduleEntryPointMethodResponse schedule) {
            enumValue(schedule.type(), Set.of("SCHEDULE"), "schedule entry point type");
            enumValue(schedule.triggerKind(), Set.of("CRON", "FIXED_DELAY", "FIXED_RATE", "JOB_HANDLER", "UNSPECIFIED"),
                    "schedule trigger kind");
            requiredString(schedule.triggerValue(), "schedule trigger value");
        } else {
            throw contract("unsupported entry point method type");
        }
        resolution(required.analysisTarget());
    }

    private void resolution(SemanticDtos.MethodTargetResolutionResponse resolution) {
        SemanticDtos.MethodTargetResolutionResponse required = requiredObject(resolution, "method target resolution");
        String status = enumValue(required.status(), RESOLUTION_STATUSES, "method target resolution status");
        requiredMethodTargets(required.candidates(), "method target resolution candidate");
        requiredString(required.reasonCode(), "method target resolution reason code");
        if ("RESOLVED".equals(status) && Objects.isNull(required.target())) {
            throw contract("resolved method target must not be null");
        }
        if (Objects.nonNull(required.target())) {
            methodTarget(required.target());
        }
    }

    private void graphTraversal(SemanticDtos.GraphTraversal traversal) {
        SemanticDtos.GraphTraversal required = requiredObject(traversal, "graph traversal");
        range(requiredInteger(required.requestedDepth(), "graph requested depth"), 1, 2, "graph requested depth");
        minimum(requiredInteger(required.expandedNodeCount(), "graph expanded node count"), 0,
                "graph expanded node count");
        minimum(requiredInteger(required.nodeBudget(), "graph node budget"), 0, "graph node budget");
        requiredBoolean(required.rootDirectCallsComplete(), "graph root direct calls complete");
        enumValue(required.limitReason(), LIMIT_REASONS, "graph limit reason");
    }

    private void graphNode(SemanticDtos.GraphNode node) {
        SemanticDtos.GraphNode required = requiredObject(node, "graph node");
        requiredString(required.nodeId(), "graph node ID");
        enumValue(required.contentState(), CONTENT_STATES, "graph node content state");
        enumValue(required.traversalState(), TRAVERSAL_STATES, "graph node traversal state");
        enumValue(required.dispatchKind(), DISPATCH_KINDS, "graph node dispatch kind");
        if (Objects.nonNull(required.target())) {
            methodTarget(required.target());
        }
        if (Objects.nonNull(required.declarationRange())) {
            textRange(required.declarationRange(), "graph declaration range");
        }
    }

    private void graphEdge(SemanticDtos.GraphEdge edge) {
        SemanticDtos.GraphEdge required = requiredObject(edge, "graph edge");
        requiredString(required.callerNodeId(), "graph caller node ID");
        requiredString(required.calleeNodeId(), "graph callee node ID");
        sourceRange(required.callSite(), "graph call site");
        requiredString(required.callExpression(), "graph call expression");
        enumValue(required.resolutionStrategy(), EDGE_STRATEGIES, "graph edge strategy");
        enumValue(required.category(), EDGE_CATEGORIES, "graph edge category");
        requiredStrings(required.evidence(), "graph edge evidence");
    }

    private void graphWarning(SemanticDtos.GraphWarning warning) {
        SemanticDtos.GraphWarning required = requiredObject(warning, "graph warning");
        enumValue(required.code(), WARNING_CODES, "graph warning code");
        requiredString(required.message(), "graph warning message");
        requiredString(required.nodeId(), "graph warning node ID");
        if (Objects.nonNull(required.callSite())) {
            sourceRange(required.callSite(), "graph warning call site");
        }
        requiredMethodTargets(required.candidates(), "graph warning candidate");
    }

    private void graphError(SemanticDtos.GraphError error) {
        SemanticDtos.GraphError required = requiredObject(error, "graph error");
        enumValue(required.code(), ERROR_CODES, "graph error code");
        requiredString(required.message(), "graph error message");
        requiredString(required.nodeId(), "graph error node ID");
    }

    private void sourceRange(SemanticDtos.SourceRangePayload range, String description) {
        SemanticDtos.SourceRangePayload required = requiredObject(range, description);
        nonblank(required.sourceFile(), description + " source file");
        textRange(required.range(), description + " range");
    }

    private void textRange(SemanticDtos.TextRangePayload range, String description) {
        SemanticDtos.TextRangePayload required = requiredObject(range, description);
        position(required.start(), description + " start");
        position(required.end(), description + " end");
    }

    private void position(SemanticDtos.Position position, String description) {
        SemanticDtos.Position required = requiredObject(position, description);
        minimum(requiredInteger(required.line(), description + " line"), 0, description + " line");
        minimum(requiredInteger(required.character(), description + " character"), 0, description + " character");
    }

    private void sourceFile(String value) {
        String required = requiredString(value, "method target source file");
        nonblank(required, "method target source file");
        if (required.length() > 1_024 || hasControlCharacter(required) || endsWithTerminalWhitespace(required)
                || !normalizedRelativePath(required)) {
            throw contract("method target source file has an invalid format");
        }
    }

    private boolean normalizedRelativePath(String value) {
        if (value.startsWith("/") || value.startsWith("\\") || value.contains("\\")) {
            return false;
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || (character >= 0x7f && character <= 0x9f)) {
                return true;
            }
        }
        return false;
    }

    private boolean endsWithTerminalWhitespace(String value) {
        int codePoint = value.codePointBefore(value.length());
        return Character.isWhitespace(codePoint) || codePoint == 0x00a0 || codePoint == 0x1680 || codePoint == 0x2007
                || codePoint == 0x202f;
    }

    private void javaQualifiedIdentifier(String value, boolean allowsDots, String description) {
        String required = requiredString(value, description);
        if (required.length() > 255 || (!allowsDots && required.indexOf('.') >= 0)) {
            throw contract(description + " has an invalid format");
        }
        String[] parts = allowsDots ? required.split("\\.", -1) : new String[]{required};
        for (String part : parts) {
            if (part.isEmpty() || !isJavaIdentifier(part)) {
                throw contract(description + " has an invalid format");
            }
        }
    }

    private boolean isJavaIdentifier(String value) {
        int first = value.codePointAt(0);
        if (!(Character.isLetter(first) || Character.getType(first) == Character.LETTER_NUMBER
                || Character.getType(first) == Character.CURRENCY_SYMBOL || Character.getType(first) == Character.CONNECTOR_PUNCTUATION)) {
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

    private void optionalRepositoryId(String value, String description) {
        if (Objects.nonNull(value)) {
            repositoryId(value, description);
        }
    }

    private void optionalRevision(String value, String description) {
        if (Objects.nonNull(value)) {
            revision(value, description);
        }
    }

    private List<SemanticDtos.MethodTarget> requiredMethodTargets(List<SemanticDtos.MethodTarget> values, String description) {
        List<SemanticDtos.MethodTarget> required = requiredList(values, description);
        for (SemanticDtos.MethodTarget value : required) {
            methodTarget(value);
        }
        return required;
    }

    private List<String> requiredStrings(List<String> values, String description) {
        return requiredList(values, description);
    }

    private String enumValue(String value, Set<String> supported, String description) {
        String required = requiredString(value, description);
        if (!supported.contains(required)) {
            throw contract("unsupported " + description);
        }
        return required;
    }

    private String requiredString(String value, String description) {
        if (Objects.isNull(value)) {
            throw contract(description + " must not be null");
        }
        return value;
    }

    private void nonblank(String value, String description) {
        if (!StringUtils.hasText(value)) {
            throw contract(description + " must be nonblank");
        }
    }

    private void range(int value, int minimum, int maximum, String description) {
        if (value < minimum || value > maximum) {
            throw contract(description + " is outside its supported range");
        }
    }

    private void minimum(int value, int minimum, String description) {
        if (value < minimum) {
            throw contract(description + " is outside its supported range");
        }
    }

    private int requiredInteger(Integer value, String description) {
        return requiredObject(value, description);
    }

    private boolean requiredBoolean(Boolean value, String description) {
        return requiredObject(value, description);
    }

    private <T> T requiredObject(T value, String description) {
        if (Objects.isNull(value)) {
            throw contract(description + " must not be null");
        }
        return value;
    }

    private <T> List<T> requiredList(List<T> values, String description) {
        if (Objects.isNull(values)) {
            throw contract(description + " list must not be null");
        }
        List<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(requiredObject(value, description));
        }
        return List.copyOf(result);
    }

    private CapabilityExecutionContractException contract(String message) {
        return new CapabilityExecutionContractException(message);
    }
}
