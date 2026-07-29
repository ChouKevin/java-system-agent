package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import com.java.system.agent.answering.domain.candidate.AnalysisCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.RouteCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.EvidenceWarning;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.evidence.SourceRange;
import com.java.system.agent.answering.domain.observation.CapabilityObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.CapabilityInvocation;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 將 Java Semantic Service v1 DTO 依原始順序轉成 answering 未配發的結果
 */
public final class JavaSemanticResultMapper {

    private static final String SOURCE_SERVICE = "java-semantic-service";
    private static final String METHOD_TARGET_KEY_VERSION = "mt1";
    private static final int MAX_TEXT_LENGTH = 1_000;
    private static final String METHOD_TARGET_TERMINATOR = ":!";

    private final JavaSemanticProviderSchemaValidator schemaValidator = new JavaSemanticProviderSchemaValidator();

    public List<RepositoryDescriptor> repositories(List<SemanticDtos.RepositoryStatusResponse> responses) {
        List<SemanticDtos.RepositoryStatusResponse> required = schemaValidator.repositories(responses);
        List<RepositoryDescriptor> descriptors = new ArrayList<>();
        for (SemanticDtos.RepositoryStatusResponse response : required) {
            descriptors.add(new RepositoryDescriptor(new RepositoryId(response.repoId()),
                    description(response.displayName(), "repository")));
        }
        return List.copyOf(descriptors);
    }

    public RepositoryRevision repositoryRevision(SemanticDtos.RepositoryStatusResponse response) {
        SemanticDtos.RepositoryStatusResponse required = repositoryStatus(response);
        if (Objects.isNull(required.currentRevision())) {
            throw contract("repository current revision is unavailable");
        }
        return new RepositoryRevision(required.currentRevision());
    }

    SemanticDtos.RepositoryStatusResponse repositoryStatus(SemanticDtos.RepositoryStatusResponse response) {
        return schemaValidator.repository(response);
    }

    public CapabilityExecutionResult listEntryPoints(CapabilityInvocation invocation,
                                                      SemanticDtos.EntryPointsResponse response) {
        requireProviderObject(invocation, "capability invocation");
        SemanticDtos.EntryPointsResponse requiredResponse = schemaValidator.entryPoints(response);
        RepositoryId repositoryId = new RepositoryId(requiredResponse.repoId());
        RepositoryRevision revision = new RepositoryRevision(requiredResponse.analyzedRevision());
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>();
        for (SemanticDtos.EntryPointClassResponse entryPoint : requiredList(requiredResponse.entryPoints(), "entry point")) {
            String classDescription = description(entryPoint.description(), "entry point");
            for (SemanticDtos.EntryPointMethodResponse method : requiredList(entryPoint.methods(), "entry point method")) {
                String description = description(classDescription + " " + method.description(), "entry point method");
                if (method instanceof SemanticDtos.ApiEntryPointMethodResponse apiMethod) {
                    candidates.add(new RouteCandidate(repositoryId, revision,
                            apiMethod.apiUrl(), description));
                }
                addResolutionCandidates(repositoryId, revision, method.analysisTarget(), description, candidates,
                        observations);
            }
        }
        return succeeded(candidates, List.of(), observations);
    }

    public CapabilityExecutionResult apiRoutes(SemanticDtos.ApiRouteCandidatesResponse response) {
        SemanticDtos.ApiRouteCandidatesResponse requiredResponse = schemaValidator.apiRoutes(response);
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>();
        for (SemanticDtos.ApiRouteCandidateResponse route : requiredList(requiredResponse.candidates(), "API route candidate")) {
            RepositoryId repositoryId = new RepositoryId(route.repoId());
            RepositoryRevision revision = new RepositoryRevision(route.analyzedRevision());
            String description = description(route.httpMethod() + " " + route.routeTemplate() + " " + route.className()
                    + "#" + route.methodName(), "API route");
            candidates.add(new RouteCandidate(repositoryId, revision,
                    route.routeTemplate(), description));
            addResolutionCandidates(repositoryId, revision, route.analysisTarget(), description, candidates, observations);
        }
        for (SemanticDtos.ApiRouteObservationResponse observation : requiredList(requiredResponse.observations(),
                "API route observation")) {
            if (!"TRUNCATED_CANDIDATES".equals(requiredText(observation.code(), "API route observation code"))) {
                throw contract("unsupported API route observation code");
            }
            observations.add(observation(ObservationCode.TRUNCATED_CANDIDATES, observation.description(), List.of()));
        }
        return succeeded(candidates, List.of(), observations);
    }

    public CapabilityExecutionResult outgoingCallGraph(CapabilityInvocation invocation,
                                                        SemanticDtos.OutgoingCallGraphResponse response) {
        SemanticDtos.OutgoingCallGraphResponse required = requireProviderObject(response, "outgoing call graph response");
        return callGraph(invocation, required.status(), required.analyzedRevision(), required.rootNodeId(),
                required.traversal(), required.nodes(), required.edges(), required.warnings(), required.errors());
    }

    public CapabilityExecutionResult incomingCallGraph(CapabilityInvocation invocation,
                                                        SemanticDtos.IncomingCallGraphResponse response) {
        SemanticDtos.IncomingCallGraphResponse required = requireProviderObject(response, "incoming call graph response");
        return callGraph(invocation, required.status(), required.analyzedRevision(), required.rootNodeId(),
                required.traversal(), required.nodes(), required.edges(), required.warnings(), required.errors());
    }

    public SemanticDtos.MethodTarget methodTarget(SemanticTarget target) {
        requireProviderObject(target, "semantic target");
        List<String> parts = decodeMethodTargetKey(target.key());
        if (parts.size() < 4) {
            throw contract("semantic target does not contain an exact method target");
        }
        List<String> parameterTypes = new ArrayList<>();
        for (int index = 4; index < parts.size(); index++) {
            parameterTypes.add(parts.get(index));
        }
        SemanticDtos.MethodTarget methodTarget = new SemanticDtos.MethodTarget(parts.get(0), parts.get(1), parts.get(2),
                parts.get(3), List.copyOf(parameterTypes));
        return schemaValidator.methodTarget(methodTarget);
    }

    public SemanticTarget semanticTarget(SemanticDtos.MethodTarget target) {
        SemanticDtos.MethodTarget requiredTarget = schemaValidator.methodTarget(target);
        List<String> parameters = requiredList(requiredTarget.parameterTypes(), "method target parameter type");
        List<String> keyParts = new ArrayList<>();
        keyParts.add(requiredTarget.sourceFile());
        keyParts.add(requiredTarget.packageName());
        keyParts.add(requiredTarget.className());
        keyParts.add(requiredTarget.methodName());
        for (String parameter : parameters) {
            keyParts.add(parameter);
        }
        return new SemanticTarget(SemanticTargetKind.SYMBOL, encodeMethodTargetKey(keyParts), Optional.empty());
    }

    private CapabilityExecutionResult callGraph(CapabilityInvocation invocation, String status, String analyzedRevision,
                                                String rootNodeId, SemanticDtos.GraphTraversal traversal,
                                                List<SemanticDtos.GraphNode> nodes, List<SemanticDtos.GraphEdge> edges,
                                                List<SemanticDtos.GraphWarning> warnings,
                                                List<SemanticDtos.GraphError> errors) {
        requireProviderObject(invocation, "capability invocation");
        schemaValidator.graph(status, analyzedRevision, rootNodeId, traversal, nodes, edges, warnings, errors);
        RepositoryId repositoryId = selectedTarget(invocation).repositoryId();
        RepositoryRevision revision = new RepositoryRevision(analyzedRevision);
        List<SemanticDtos.GraphNode> requiredNodes = requiredList(nodes, "graph node");
        List<SemanticDtos.GraphEdge> requiredEdges = requiredList(edges, "graph edge");
        List<SemanticDtos.GraphWarning> requiredWarnings = requiredList(warnings, "graph warning");
        List<SemanticDtos.GraphError> requiredErrors = requiredList(errors, "graph error");
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>();
        SemanticDtos.GraphNode root = responseRoot(rootNodeId, requiredNodes);
        for (SemanticDtos.GraphNode node : requiredNodes) {
            if (Objects.nonNull(node.target())) {
                candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(node.target()),
                        description("graph node " + node.nodeId(), "graph node")));
            }
            addNodeObservation(node, observations);
        }
        for (SemanticDtos.GraphEdge edge : requiredEdges) {
            if ("UNRESOLVED_GUESS".equals(edge.category())) {
                observations.add(observation(ObservationCode.UNRESOLVED_CALL, edge.callExpression(), List.of()));
            }
            if ("RESOLVED_OPAQUE".equals(edge.category())) {
                observations.add(observation(ObservationCode.OPAQUE_EXTERNAL_CALL, edge.callExpression(), List.of()));
            }
        }
        for (SemanticDtos.GraphWarning warning : requiredWarnings) {
            ObservationCode code = switch (warning.code()) {
                case "DESCENDANT_CALL_AMBIGUOUS" -> ObservationCode.AMBIGUOUS_SEMANTIC_TARGET;
                case "DESCENDANT_CALL_UNRESOLVED" -> ObservationCode.UNRESOLVED_CALL;
                case "INCOMING_CALLER_REJECTED" -> ObservationCode.MISSING_SOURCE;
                case "NODE_BUDGET_REACHED" -> ObservationCode.PARTIAL_GRAPH;
                default -> throw contract("unsupported graph warning code");
            };
            List<AnalysisCandidate> warningCandidates = targetCandidates(repositoryId, revision,
                    requiredList(warning.candidates(), "graph warning candidate"), warning.message());
            candidates.addAll(warningCandidates);
            observations.add(observation(code, warning.message(), warningCandidates));
        }
        for (SemanticDtos.GraphError error : requiredErrors) {
            observations.add(observation(ObservationCode.UNRESOLVED_CALL, error.message(), List.of()));
        }
        if ("PARTIAL".equals(status)) {
            observations.add(observation(ObservationCode.PARTIAL_GRAPH, "Java Semantic Service returned a partial graph",
                    List.of()));
        }
        String content = graphContent(rootNodeId, traversal, requiredNodes, requiredEdges, requiredWarnings, requiredErrors);
        EvidenceRef evidence = new EvidenceRef(SOURCE_SERVICE, repositoryId, revision, semanticTarget(root.target()), content,
                evidenceWarnings(requiredWarnings, requiredErrors), JavaSemanticArtifactDigest.fromContent(content));
        return succeeded(candidates, List.of(evidence), observations);
    }

    private void addResolutionCandidates(RepositoryId repositoryId, RepositoryRevision revision,
                                         SemanticDtos.MethodTargetResolutionResponse resolution, String description,
                                         List<AnalysisCandidate> candidates,
                                         List<CapabilityObservation> observations) {
        requireProviderObject(resolution, "method target resolution");
        String status = resolution.status();
        if ("RESOLVED".equals(status)) {
            SemanticDtos.MethodTarget target = requireProviderObject(resolution.target(), "resolved target");
            candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(target), description));
            return;
        }
        if ("AMBIGUOUS".equals(status)) {
            List<AnalysisCandidate> ambiguousCandidates = targetCandidates(repositoryId, revision,
                    requiredList(resolution.candidates(), "ambiguous method target"), description);
            candidates.addAll(ambiguousCandidates);
            observations.add(observation(ObservationCode.AMBIGUOUS_SEMANTIC_TARGET, resolution.reasonCode(),
                    ambiguousCandidates));
            return;
        }
        if ("UNRESOLVED".equals(status)) {
            observations.add(observation(ObservationCode.UNRESOLVED_CALL, resolution.reasonCode(), List.of()));
            return;
        }
        throw contract("unsupported method target resolution status");
    }

    private List<AnalysisCandidate> targetCandidates(RepositoryId repositoryId, RepositoryRevision revision,
                                                     List<SemanticDtos.MethodTarget> targets, String description) {
        List<AnalysisCandidate> candidates = new ArrayList<>();
        for (SemanticDtos.MethodTarget target : targets) {
            candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(target),
                    description(description, "semantic target")));
        }
        return List.copyOf(candidates);
    }

    private void addNodeObservation(SemanticDtos.GraphNode node, List<CapabilityObservation> observations) {
        String contentState = node.contentState();
        String traversalState = node.traversalState();
        if ("EXTERNAL".equals(contentState) || "EXTERNAL".equals(traversalState)
                || "OPAQUE".equals(traversalState)) {
            observations.add(observation(ObservationCode.OPAQUE_EXTERNAL_CALL, "graph node " + node.nodeId(), List.of()));
        }
        if ("TARGET_ONLY".equals(contentState) || "BUDGET_CUTOFF".equals(traversalState)) {
            observations.add(observation(ObservationCode.MISSING_SOURCE, "graph node " + node.nodeId(), List.of()));
        }
    }

    private SemanticTargetCandidate selectedTarget(CapabilityInvocation invocation) {
        List<IssuedCandidate> selected = invocation.candidates();
        if (selected.size() != 1 || !(selected.getFirst().candidate() instanceof SemanticTargetCandidate candidate)) {
            throw contract("call graph requires exactly one semantic target candidate");
        }
        return candidate;
    }

    private CapabilityExecutionResult.Succeeded succeeded(List<AnalysisCandidate> candidates, List<EvidenceRef> evidence,
                                                           List<CapabilityObservation> observations) {
        return new CapabilityExecutionResult.Succeeded(candidates, evidence, observations);
    }

    private CapabilityObservation observation(ObservationCode code, String description,
                                              List<AnalysisCandidate> candidates) {
        return new CapabilityObservation(code, description(description, "provider observation"), candidates, List.of(),
                SOURCE_SERVICE);
    }

    private List<EvidenceWarning> evidenceWarnings(List<SemanticDtos.GraphWarning> warnings,
                                                   List<SemanticDtos.GraphError> errors) {
        List<EvidenceWarning> result = new ArrayList<>();
        for (SemanticDtos.GraphWarning warning : warnings) {
            result.add(new EvidenceWarning(warning.code(), description(warning.message(), "graph warning")));
        }
        for (SemanticDtos.GraphError error : errors) {
            result.add(new EvidenceWarning(error.code(), description(error.message(), "graph error")));
        }
        return List.copyOf(result);
    }

    private String graphContent(String rootNodeId, SemanticDtos.GraphTraversal traversal,
                                List<SemanticDtos.GraphNode> nodes, List<SemanticDtos.GraphEdge> edges,
                                List<SemanticDtos.GraphWarning> warnings, List<SemanticDtos.GraphError> errors) {
        List<String> parts = new ArrayList<>();
        parts.add("root=" + rootNodeId);
        parts.add("traversal=" + traversal.requestedDepth() + "/" + traversal.expandedNodeCount() + "/"
                + traversal.nodeBudget() + "/" + traversal.rootDirectCallsComplete() + "/" + traversal.limitReason());
        for (SemanticDtos.GraphNode node : nodes) {
            parts.add("node=" + node.nodeId() + ":" + node.contentState() + ":" + node.traversalState() + ":"
                    + node.dispatchKind() + ":target=" + targetSummary(node.target()) + ":external="
                    + providerText(node.externalSymbol()) + ":body=" + providerText(node.methodBody()) + ":range="
                    + sourceRangeSummary(node.declarationRange()));
        }
        for (SemanticDtos.GraphEdge edge : edges) {
            parts.add("edge=" + edge.callerNodeId() + ">" + edge.calleeNodeId() + ":" + edge.category() + ":"
                    + edge.resolutionStrategy() + ":" + singleLine(edge.callExpression()) + ":evidence="
                    + summaries(edge.evidence()) + ":call-site=" + sourceRangeSummary(edge.callSite()));
        }
        for (SemanticDtos.GraphWarning warning : warnings) {
            parts.add("warning=" + warning.code() + ":"
                    + singleLine(warning.message()) + ":node=" + warning.nodeId() + ":call="
                    + providerText(warning.callExpression()) + ":call-site=" + sourceRangeSummary(warning.callSite())
                    + ":candidates=" + targetSummaries(warning.candidates()));
        }
        for (SemanticDtos.GraphError error : errors) {
            parts.add("error=" + error.code() + ":" + singleLine(error.message()) + ":node=" + error.nodeId());
        }
        return String.join("; ", parts);
    }

    private SemanticDtos.GraphNode responseRoot(String rootNodeId, List<SemanticDtos.GraphNode> nodes) {
        SemanticDtos.GraphNode root = null;
        for (SemanticDtos.GraphNode node : nodes) {
            if (rootNodeId.equals(node.nodeId())) {
                if (Objects.nonNull(root)) {
                    throw contract("graph response must contain exactly one root node");
                }
                root = node;
            }
        }
        SemanticDtos.GraphNode requiredRoot = requireProviderObject(root, "graph root node");
        if (Objects.isNull(requiredRoot.target())) {
            throw contract("graph root node target must not be null");
        }
        return requiredRoot;
    }

    private String targetSummary(SemanticDtos.MethodTarget target) {
        if (Objects.isNull(target)) {
            return "null";
        }
        return singleLine(target.sourceFile()) + "#" + singleLine(target.className()) + "." + singleLine(target.methodName());
    }

    private String summaries(List<String> values) {
        List<String> summaries = new ArrayList<>();
        for (String value : values) {
            summaries.add(singleLine(value));
        }
        return String.join(",", summaries);
    }

    private String targetSummaries(List<SemanticDtos.MethodTarget> targets) {
        List<String> summaries = new ArrayList<>();
        for (SemanticDtos.MethodTarget target : targets) {
            summaries.add(targetSummary(target));
        }
        return String.join(",", summaries);
    }

    private String sourceRangeSummary(SemanticDtos.SourceRange range) {
        if (Objects.isNull(range)) {
            return "null";
        }
        return singleLine(range.sourceFile()) + "@" + range.start().line() + ":" + range.start().character()
                + "-" + range.end().line() + ":" + range.end().character();
    }

    private String providerText(String value) {
        return Objects.isNull(value) ? "null" : singleLine(value);
    }

    private static String description(String value, String fallback) {
        String sanitized = singleLine(value);
        return StringUtils.hasText(sanitized) ? sanitized : fallback;
    }

    private String encodeMethodTargetKey(List<String> components) {
        StringBuilder encoded = new StringBuilder(METHOD_TARGET_KEY_VERSION).append(':').append(components.size());
        for (String component : components) {
            encoded.append(':').append(component.length()).append(':').append(component);
        }
        return encoded.append(METHOD_TARGET_TERMINATOR).toString();
    }

    private List<String> decodeMethodTargetKey(String key) {
        String requiredKey = requireProviderObject(key, "semantic target key");
        String prefix = METHOD_TARGET_KEY_VERSION + ":";
        if (!requiredKey.startsWith(prefix)) {
            throw contract("semantic target key has an unsupported method target version");
        }
        int countEnd = requiredKey.indexOf(':', prefix.length());
        if (countEnd < 0) {
            throw contract("semantic target key is missing a component count delimiter");
        }
        int count = parseCanonicalNumber(requiredKey.substring(prefix.length(), countEnd), "component count");
        if (count < 4) {
            throw contract("semantic target key has too few components");
        }
        int position = countEnd;
        List<String> components = new ArrayList<>();
        for (int componentIndex = 0; componentIndex < count; componentIndex++) {
            if (position >= requiredKey.length() || requiredKey.charAt(position) != ':') {
                throw contract("semantic target key has an invalid component prefix");
            }
            int lengthEnd = requiredKey.indexOf(':', position + 1);
            if (lengthEnd < 0) {
                throw contract("semantic target key is missing a component length delimiter");
            }
            String lengthValue = requiredKey.substring(position + 1, lengthEnd);
            int length = parseCanonicalNumber(lengthValue, "component length");
            int valueStart = lengthEnd + 1;
            if (length > requiredKey.length() - valueStart) {
                throw contract("semantic target key component length exceeds remaining content");
            }
            int valueEnd = valueStart + length;
            components.add(requiredKey.substring(valueStart, valueEnd));
            position = valueEnd;
        }
        if (!requiredKey.startsWith(METHOD_TARGET_TERMINATOR, position)
                || position + METHOD_TARGET_TERMINATOR.length() != requiredKey.length()) {
            throw contract("semantic target key has trailing or missing terminal content");
        }
        return List.copyOf(components);
    }

    private int parseCanonicalNumber(String value, String description) {
        if (value.isEmpty()) {
            throw contract("semantic target key " + description + " is empty");
        }
        if (value.length() > 1 && value.charAt(0) == '0') {
            throw contract("semantic target key " + description + " is noncanonical");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw contract("semantic target key " + description + " is not numeric");
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw contract("semantic target key " + description + " is invalid");
        }
    }

    static String singleLine(String value) {
        String required = requireProviderObject(value, "provider text");
        String normalized = required.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_TEXT_LENGTH ? normalized : normalized.substring(0, MAX_TEXT_LENGTH);
    }

    static String failureDescription(String value) {
        String sanitized = singleLine(value);
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    private static String requiredText(String value, String description) {
        if (!StringUtils.hasText(value)) {
            throw contract(description + " must be nonblank");
        }
        return value.trim();
    }

    private static <T> List<T> requiredList(List<T> values, String description) {
        if (Objects.isNull(values)) {
            throw contract(description + " list must not be null");
        }
        List<T> copied = new ArrayList<>();
        for (T value : values) {
            copied.add(requireProviderObject(value, description + " must not contain null values"));
        }
        return List.copyOf(copied);
    }

    private static <T> T requireProviderObject(T value, String description) {
        if (Objects.isNull(value)) {
            throw contract(description + " must not be null");
        }
        return value;
    }

    private static CapabilityExecutionContractException contract(String message) {
        return new CapabilityExecutionContractException(message);
    }
}
