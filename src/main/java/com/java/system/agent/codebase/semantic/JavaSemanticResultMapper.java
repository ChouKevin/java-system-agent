package com.java.system.agent.codebase.semantic;

import com.java.system.agent.codebase.semantic.dto.SemanticDtos;
import com.java.system.agent.runtime.domain.candidate.AnalysisCandidate;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.RepositoryCandidate;
import com.java.system.agent.runtime.domain.candidate.RouteCandidate;
import com.java.system.agent.runtime.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceWarning;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.evidence.SourceRange;
import com.java.system.agent.runtime.domain.observation.CapabilityObservation;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 將 Java Semantic Service v1 DTO 依原始順序轉成 runtime 未配發的結果
 */
public final class JavaSemanticResultMapper {

    private static final String SOURCE_SERVICE = "java-semantic-service";
    private static final String METHOD_TARGET_KEY_VERSION = "mt1";
    private static final int MAX_TEXT_LENGTH = 1_000;

    public List<RepositoryDescriptor> repositories(List<SemanticDtos.RepositoryStatusResponse> responses) {
        List<SemanticDtos.RepositoryStatusResponse> required = requiredList(responses, "repository response");
        List<RepositoryDescriptor> descriptors = new ArrayList<>();
        for (SemanticDtos.RepositoryStatusResponse response : required) {
            descriptors.add(new RepositoryDescriptor(new RepositoryId(requiredText(response.repoId(), "repository ID")),
                    singleLine(response.displayName())));
        }
        return List.copyOf(descriptors);
    }

    public RepositoryRevision repositoryRevision(SemanticDtos.RepositoryStatusResponse response) {
        Objects.requireNonNull(response, "repository status response must not be null");
        return new RepositoryRevision(requiredText(response.currentRevision(), "repository current revision"));
    }

    public CapabilityExecutionResult listEntryPoints(CapabilityInvocation invocation,
                                                      SemanticDtos.EntryPointsResponse response) {
        Objects.requireNonNull(invocation, "capability invocation must not be null");
        Objects.requireNonNull(response, "entry-points response must not be null");
        RepositoryId repositoryId = new RepositoryId(requiredText(response.repoId(), "entry-points repository ID"));
        RepositoryRevision revision = new RepositoryRevision(requiredText(response.analyzedRevision(),
                "entry-points analyzed revision"));
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>();
        for (SemanticDtos.EntryPointClassResponse entryPoint : requiredList(response.entryPoints(), "entry point")) {
            String classDescription = singleLine(entryPoint.description());
            for (SemanticDtos.EntryPointMethodResponse method : requiredList(entryPoint.methods(), "entry point method")) {
                String description = singleLine(classDescription + " " + requiredText(method.description(),
                        "entry point method description"));
                if (method instanceof SemanticDtos.ApiEntryPointMethodResponse apiMethod) {
                    candidates.add(new RouteCandidate(repositoryId, revision,
                            requiredText(apiMethod.apiUrl(), "entry point API URL"), description));
                }
                addResolutionCandidates(repositoryId, revision, method.analysisTarget(), description, candidates,
                        observations);
            }
        }
        return succeeded(candidates, List.of(), observations);
    }

    public CapabilityExecutionResult apiRoutes(SemanticDtos.ApiRouteCandidatesResponse response) {
        Objects.requireNonNull(response, "API route candidates response must not be null");
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>();
        for (SemanticDtos.ApiRouteCandidateResponse route : requiredList(response.candidates(), "API route candidate")) {
            RepositoryId repositoryId = new RepositoryId(requiredText(route.repoId(), "route repository ID"));
            RepositoryRevision revision = new RepositoryRevision(requiredText(route.analyzedRevision(),
                    "route analyzed revision"));
            String description = singleLine(requiredText(route.httpMethod(), "route HTTP method") + " "
                    + requiredText(route.routeTemplate(), "route template") + " "
                    + requiredText(route.className(), "route class") + "#"
                    + requiredText(route.methodName(), "route method"));
            candidates.add(new RouteCandidate(repositoryId, revision,
                    requiredText(route.routeTemplate(), "route template"), description));
            addResolutionCandidates(repositoryId, revision, route.analysisTarget(), description, candidates, observations);
        }
        for (SemanticDtos.ApiRouteObservationResponse observation : requiredList(response.observations(),
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
        return callGraph(invocation, response.status(), response.analyzedRevision(), response.rootNodeId(),
                response.traversal(), response.nodes(), response.edges(), response.warnings(), response.errors());
    }

    public CapabilityExecutionResult incomingCallGraph(CapabilityInvocation invocation,
                                                        SemanticDtos.IncomingCallGraphResponse response) {
        return callGraph(invocation, response.status(), response.analyzedRevision(), response.rootNodeId(),
                response.traversal(), response.nodes(), response.edges(), response.warnings(), response.errors());
    }

    public SemanticDtos.MethodTarget methodTarget(SemanticTarget target) {
        Objects.requireNonNull(target, "semantic target must not be null");
        List<String> parts = decodeMethodTargetKey(target.key());
        if (parts.size() < 5) {
            throw contract("semantic target does not contain an exact method target");
        }
        List<String> parameterTypes = new ArrayList<>();
        for (int index = 4; index < parts.size(); index++) {
            parameterTypes.add(requiredText(parts.get(index), "method target parameter type"));
        }
        return new SemanticDtos.MethodTarget(requiredText(parts.get(0), "method target source file"), parts.get(1),
                requiredText(parts.get(2), "method target class"), requiredText(parts.get(3), "method target method"),
                List.copyOf(parameterTypes));
    }

    public SemanticTarget semanticTarget(SemanticDtos.MethodTarget target) {
        Objects.requireNonNull(target, "method target must not be null");
        List<String> parameters = requiredList(target.parameterTypes(), "method target parameter type");
        List<String> keyParts = new ArrayList<>();
        keyParts.add(requiredText(target.sourceFile(), "method target source file"));
        keyParts.add(Objects.requireNonNull(target.packageName(), "method target package must not be null"));
        keyParts.add(requiredText(target.className(), "method target class"));
        keyParts.add(requiredText(target.methodName(), "method target method"));
        for (String parameter : parameters) {
            keyParts.add(requiredText(parameter, "method target parameter type"));
        }
        return new SemanticTarget(SemanticTargetKind.SYMBOL, encodeMethodTargetKey(keyParts), Optional.empty());
    }

    private CapabilityExecutionResult callGraph(CapabilityInvocation invocation, String status, String analyzedRevision,
                                                String rootNodeId, SemanticDtos.GraphTraversal traversal,
                                                List<SemanticDtos.GraphNode> nodes, List<SemanticDtos.GraphEdge> edges,
                                                List<SemanticDtos.GraphWarning> warnings,
                                                List<SemanticDtos.GraphError> errors) {
        Objects.requireNonNull(invocation, "capability invocation must not be null");
        RepositoryId repositoryId = selectedTarget(invocation).repositoryId();
        RepositoryRevision revision = new RepositoryRevision(requiredText(analyzedRevision, "graph analyzed revision"));
        List<SemanticDtos.GraphNode> requiredNodes = requiredList(nodes, "graph node");
        List<SemanticDtos.GraphEdge> requiredEdges = requiredList(edges, "graph edge");
        List<SemanticDtos.GraphWarning> requiredWarnings = requiredList(warnings, "graph warning");
        List<SemanticDtos.GraphError> requiredErrors = requiredList(errors, "graph error");
        List<AnalysisCandidate> candidates = new ArrayList<>();
        List<CapabilityObservation> observations = new ArrayList<>();
        for (SemanticDtos.GraphNode node : requiredNodes) {
            if (Objects.nonNull(node.target())) {
                candidates.add(new SemanticTargetCandidate(repositoryId, revision, semanticTarget(node.target()),
                        singleLine("graph node " + requiredText(node.nodeId(), "graph node ID"))));
            }
            addNodeObservation(node, observations);
        }
        for (SemanticDtos.GraphEdge edge : requiredEdges) {
            if ("UNRESOLVED_GUESS".equals(requiredText(edge.category(), "graph edge category"))) {
                observations.add(observation(ObservationCode.UNRESOLVED_CALL, edge.callExpression(), List.of()));
            }
            if ("EXTERNAL_LIBRARY".equals(requiredText(edge.resolutionStrategy(), "graph edge strategy"))) {
                observations.add(observation(ObservationCode.OPAQUE_EXTERNAL_CALL, edge.callExpression(), List.of()));
            }
        }
        for (SemanticDtos.GraphWarning warning : requiredWarnings) {
            ObservationCode code = switch (requiredText(warning.code(), "graph warning code")) {
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
        if ("PARTIAL".equals(requiredText(status, "graph status"))) {
            observations.add(observation(ObservationCode.PARTIAL_GRAPH, "Java Semantic Service returned a partial graph",
                    List.of()));
        } else if (!"SUCCESS".equals(status)) {
            throw contract("unsupported graph status");
        }
        SemanticTarget requestedTarget = selectedTarget(invocation).semanticTarget();
        String content = graphContent(rootNodeId, traversal, requiredNodes, requiredEdges, requiredWarnings, requiredErrors);
        EvidenceRef evidence = new EvidenceRef(SOURCE_SERVICE, repositoryId, revision, requestedTarget, content,
                evidenceWarnings(requiredWarnings, requiredErrors), JavaSemanticArtifactDigest.fromContent(content));
        return succeeded(candidates, List.of(evidence), observations);
    }

    private void addResolutionCandidates(RepositoryId repositoryId, RepositoryRevision revision,
                                         SemanticDtos.MethodTargetResolutionResponse resolution, String description,
                                         List<AnalysisCandidate> candidates,
                                         List<CapabilityObservation> observations) {
        Objects.requireNonNull(resolution, "method target resolution must not be null");
        String status = requiredText(resolution.status(), "method target resolution status");
        if ("RESOLVED".equals(status)) {
            SemanticDtos.MethodTarget target = Objects.requireNonNull(resolution.target(), "resolved target must not be null");
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
                    singleLine(description)));
        }
        return List.copyOf(candidates);
    }

    private void addNodeObservation(SemanticDtos.GraphNode node, List<CapabilityObservation> observations) {
        String contentState = requiredText(node.contentState(), "graph node content state");
        String traversalState = requiredText(node.traversalState(), "graph node traversal state");
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
        return new CapabilityObservation(code, singleLine(description), candidates, List.of(), SOURCE_SERVICE);
    }

    private List<EvidenceWarning> evidenceWarnings(List<SemanticDtos.GraphWarning> warnings,
                                                   List<SemanticDtos.GraphError> errors) {
        List<EvidenceWarning> result = new ArrayList<>();
        for (SemanticDtos.GraphWarning warning : warnings) {
            result.add(new EvidenceWarning(requiredText(warning.code(), "graph warning code"),
                    singleLine(warning.message())));
        }
        for (SemanticDtos.GraphError error : errors) {
            result.add(new EvidenceWarning(requiredText(error.code(), "graph error code"), singleLine(error.message())));
        }
        return List.copyOf(result);
    }

    private String graphContent(String rootNodeId, SemanticDtos.GraphTraversal traversal,
                                List<SemanticDtos.GraphNode> nodes, List<SemanticDtos.GraphEdge> edges,
                                List<SemanticDtos.GraphWarning> warnings, List<SemanticDtos.GraphError> errors) {
        Objects.requireNonNull(traversal, "graph traversal must not be null");
        List<String> parts = new ArrayList<>();
        parts.add("root=" + requiredText(rootNodeId, "graph root node ID"));
        parts.add("traversal=" + traversal.requestedDepth() + "/" + traversal.expandedNodeCount() + "/"
                + traversal.nodeBudget() + "/" + requiredText(traversal.limitReason(), "graph limit reason"));
        for (SemanticDtos.GraphNode node : nodes) {
            parts.add("node=" + requiredText(node.nodeId(), "graph node ID") + ":"
                    + requiredText(node.contentState(), "graph node content state") + ":"
                    + requiredText(node.traversalState(), "graph node traversal state"));
        }
        for (SemanticDtos.GraphEdge edge : edges) {
            parts.add("edge=" + requiredText(edge.callerNodeId(), "graph caller node ID") + ">"
                    + requiredText(edge.calleeNodeId(), "graph callee node ID") + ":"
                    + requiredText(edge.category(), "graph edge category"));
        }
        for (SemanticDtos.GraphWarning warning : warnings) {
            parts.add("warning=" + requiredText(warning.code(), "graph warning code") + ":"
                    + singleLine(warning.message()));
        }
        for (SemanticDtos.GraphError error : errors) {
            parts.add("error=" + requiredText(error.code(), "graph error code") + ":" + singleLine(error.message()));
        }
        return singleLine(String.join("; ", parts));
    }

    private String encodeMethodTargetKey(List<String> components) {
        StringBuilder encoded = new StringBuilder(METHOD_TARGET_KEY_VERSION);
        for (String component : components) {
            encoded.append(':').append(component.length()).append(':').append(component);
        }
        return encoded.toString();
    }

    private List<String> decodeMethodTargetKey(String key) {
        String requiredKey = requiredText(key, "semantic target key");
        if (!requiredKey.startsWith(METHOD_TARGET_KEY_VERSION)) {
            throw contract("semantic target key has an unsupported method target version");
        }
        int position = METHOD_TARGET_KEY_VERSION.length();
        List<String> components = new ArrayList<>();
        while (position < requiredKey.length()) {
            if (requiredKey.charAt(position) != ':') {
                throw contract("semantic target key has an invalid component prefix");
            }
            int lengthEnd = requiredKey.indexOf(':', position + 1);
            if (lengthEnd < 0) {
                throw contract("semantic target key is missing a component length delimiter");
            }
            String lengthValue = requiredKey.substring(position + 1, lengthEnd);
            int length = parseComponentLength(lengthValue);
            int valueStart = lengthEnd + 1;
            if (length > requiredKey.length() - valueStart) {
                throw contract("semantic target key component length exceeds remaining content");
            }
            int valueEnd = valueStart + length;
            components.add(requiredKey.substring(valueStart, valueEnd));
            position = valueEnd;
        }
        return List.copyOf(components);
    }

    private int parseComponentLength(String value) {
        if (value.isEmpty()) {
            throw contract("semantic target key component length is empty");
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                throw contract("semantic target key component length is not numeric");
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw contract("semantic target key component length is invalid");
        }
    }

    static String singleLine(String value) {
        String required = requiredText(value, "provider text");
        String normalized = required.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_TEXT_LENGTH ? normalized : normalized.substring(0, MAX_TEXT_LENGTH);
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
            copied.add(Objects.requireNonNull(value, description + " must not contain null values"));
        }
        return List.copyOf(copied);
    }

    private static CapabilityExecutionContractException contract(String message) {
        return new CapabilityExecutionContractException(message);
    }
}
