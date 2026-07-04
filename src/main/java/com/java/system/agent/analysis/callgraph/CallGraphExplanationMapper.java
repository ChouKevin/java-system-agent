package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.CallNode;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.MethodId;
import com.java.system.agent.analysis.model.ResolutionStrategy;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CallGraphExplanationMapper {

    private static final double UNRESOLVED_CONFIDENCE = 0.10;
    private static final double DATA_ACCESS_CONFIDENCE = 0.90;
    private static final double INTERFACE_SINGLE_IMPL_CONFIDENCE = 0.85;
    private static final double INTERFACE_MULTI_IMPL_CONFIDENCE = 0.60;
    private static final double EXTERNAL_LIB_CONFIDENCE = 0.50;
    private static final double HEURISTIC_CONFIDENCE = 0.70;

    public ExplainableCallGraph map(String repoId, CallGraph root, FlattenedCallGraph legacyFlattened) {
        if (root == null) {
            return new ExplainableCallGraph(null, List.of(), List.of(), Map.of(), legacyFlattened);
        }

        LinkedHashMap<MethodId, CallNode> nodesByMethodId = new LinkedHashMap<>();
        List<CallEdge> edges = new ArrayList<>();
        MethodId rootMethodId = toMethodId(repoId, root);

        collect(repoId, root, nodesByMethodId, edges);

        return new ExplainableCallGraph(
                rootMethodId,
                List.copyOf(nodesByMethodId.values()),
                List.copyOf(edges),
                immutableMap(root.getRelatedClasses()),
                legacyFlattened);
    }

    private void collect(String repoId, CallGraph node,
            LinkedHashMap<MethodId, CallNode> nodesByMethodId, List<CallEdge> edges) {
        if (node == null) {
            return;
        }

        MethodId callerId = toMethodId(repoId, node);
        nodesByMethodId.putIfAbsent(callerId, toCallNode(callerId, node));

        if (CollectionUtils.isEmpty(node.getCalledMethods())) {
            return;
        }

        for (CallGraph child : node.getCalledMethods()) {
            if (child == null) {
                continue;
            }
            MethodId calleeId = toMethodId(repoId, child);
            Resolution resolution = resolve(node, child);
            edges.add(new CallEdge(
                    callerId,
                    calleeId,
                    calleeId.methodName(),
                    resolution.sourceFile(),
                    resolution.lineNumber(),
                    resolution.resolutionStrategy(),
                    resolution.confidence(),
                    resolution.evidence(),
                    resolution.warnings()));
            collect(repoId, child, nodesByMethodId, edges);
        }
    }

    private CallNode toCallNode(MethodId methodId, CallGraph node) {
        return new CallNode(
                methodId,
                node.getSignature(),
                node.getCallType(),
                node.getSourceFile(),
                node.getStartLine(),
                node.getEndLine(),
                immutableMap(node.getAnnotations()),
                node.getCode());
    }

    private MethodId toMethodId(String repoId, CallGraph node) {
        ParsedSignature parsedSignature = parseSignature(node.getSignature());
        String packageName = parsedSignature.packageName();
        String className = parsedSignature.className();
        String methodName = parsedSignature.methodName();
        List<String> parameterTypes = parsedSignature.parameterTypes();

        if (!StringUtils.hasText(packageName)) {
            packageName = node.getPackagePath();
        }
        if (!StringUtils.hasText(className)) {
            className = node.getClassName();
        }
        if (!StringUtils.hasText(methodName)) {
            methodName = node.getMethodName();
        }

        return new MethodId(repoId, packageName, className, methodName, parameterTypes);
    }

    private ParsedSignature parseSignature(String signature) {
        if (!StringUtils.hasText(signature)) {
            return new ParsedSignature(null, null, null, List.of());
        }

        int separatorIndex = signature.indexOf('#');
        if (separatorIndex < 0) {
            return new ParsedSignature(null, null, null, List.of());
        }

        String owner = signature.substring(0, separatorIndex);
        String declaration = signature.substring(separatorIndex + 1);
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(declaration)) {
            return new ParsedSignature(null, null, null, List.of());
        }

        int ownerClassSeparator = owner.lastIndexOf('.');
        String packageName = ownerClassSeparator >= 0 ? owner.substring(0, ownerClassSeparator) : null;
        String className = ownerClassSeparator >= 0 ? owner.substring(ownerClassSeparator + 1) : owner;
        String methodName = parseMethodName(declaration);
        List<String> parameterTypes = parseParameterTypes(declaration);

        return new ParsedSignature(packageName, className, methodName, parameterTypes);
    }

    private String parseMethodName(String declaration) {
        String methodPart = declaration;
        int parameterStart = declaration.indexOf('(');
        if (parameterStart >= 0) {
            methodPart = declaration.substring(0, parameterStart);
        }
        String trimmedMethodPart = methodPart.trim();
        if (!StringUtils.hasText(trimmedMethodPart)) {
            return null;
        }
        int lastWhitespace = trimmedMethodPart.lastIndexOf(' ');
        if (lastWhitespace >= 0) {
            return trimmedMethodPart.substring(lastWhitespace + 1);
        }
        return trimmedMethodPart;
    }

    private List<String> parseParameterTypes(String declaration) {
        int parameterStart = declaration.indexOf('(');
        int parameterEnd = declaration.lastIndexOf(')');
        if (parameterStart < 0 || parameterEnd <= parameterStart + 1) {
            return List.of();
        }

        String parameterPart = declaration.substring(parameterStart + 1, parameterEnd).trim();
        if (!StringUtils.hasText(parameterPart)) {
            return List.of();
        }

        return Arrays.stream(parameterPart.split(","))
                .map(this::parseParameterType)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String parseParameterType(String parameter) {
        String trimmedParameter = parameter.trim();
        if (!StringUtils.hasText(trimmedParameter)) {
            return null;
        }

        List<String> parts = Arrays.stream(trimmedParameter.split("\\s+"))
                .filter(part -> !"final".equals(part))
                .filter(part -> !part.startsWith("@"))
                .toList();
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(" ", parts.subList(0, parts.size() - 1));
    }

    private Resolution resolve(CallGraph caller, CallGraph callee) {
        CallResolutionEvidence explicitEvidence = callee.getResolutionEvidence();
        if (explicitEvidence != null) {
            return new Resolution(
                    explicitEvidence.resolutionStrategy(),
                    explicitEvidence.confidence(),
                    immutableList(explicitEvidence.evidence()),
                    immutableList(explicitEvidence.warnings()),
                    explicitEvidence.sourceFile(),
                    explicitEvidence.lineNumber());
        }
        if (CallType.UNRESOLVED.equals(callee.getCallType())) {
            return new Resolution(
                    ResolutionStrategy.UNRESOLVED,
                    UNRESOLVED_CONFIDENCE,
                    List.of("FALLBACK_CALL_TYPE_UNRESOLVED"),
                    List.of("Call could not be resolved"),
                    null,
                    null);
        }
        if (CallType.DATA_ACCESS.equals(callee.getCallType())) {
            return new Resolution(
                    ResolutionStrategy.MYBATIS_MAPPER,
                    DATA_ACCESS_CONFIDENCE,
                    List.of("FALLBACK_CALL_TYPE_DATA_ACCESS"),
                    List.of(),
                    null,
                    null);
        }
        if (CallType.INTERFACE.equals(caller.getCallType())) {
            int implementationCount = CollectionUtils.isEmpty(caller.getCalledMethods())
                    ? 0
                    : caller.getCalledMethods().size();
            if (implementationCount == 1) {
                return new Resolution(
                        ResolutionStrategy.INTERFACE_SINGLE_IMPL,
                        INTERFACE_SINGLE_IMPL_CONFIDENCE,
                        List.of("FALLBACK_CALL_TYPE_HEURISTIC"),
                        List.of(),
                        null,
                        null);
            }
            if (implementationCount > 1) {
                return new Resolution(
                        ResolutionStrategy.INTERFACE_MULTI_IMPL,
                        INTERFACE_MULTI_IMPL_CONFIDENCE,
                        List.of("FALLBACK_CALL_TYPE_HEURISTIC"),
                        List.of("Multiple interface implementations matched"),
                        null,
                        null);
            }
        }
        if (CallType.EXTERNAL_LIB.equals(callee.getCallType())) {
            return new Resolution(
                    ResolutionStrategy.UNKNOWN,
                    EXTERNAL_LIB_CONFIDENCE,
                    List.of("FALLBACK_CALL_TYPE_HEURISTIC"),
                    List.of(),
                    null,
                    null);
        }
        return new Resolution(
                ResolutionStrategy.HEURISTIC_NAME_MATCH,
                HEURISTIC_CONFIDENCE,
                List.of("FALLBACK_CALL_TYPE_HEURISTIC"),
                List.of(),
                null,
                null);
    }

    private Map<String, String> immutableMap(Map<String, String> values) {
        if (CollectionUtils.isEmpty(values)) {
            return Map.of();
        }
        return Map.copyOf(values);
    }

    private List<String> immutableList(List<String> values) {
        if (CollectionUtils.isEmpty(values)) {
            return List.of();
        }
        return List.copyOf(values);
    }

    private record ParsedSignature(
            String packageName,
            String className,
            String methodName,
            List<String> parameterTypes) {
    }

    private record Resolution(
            ResolutionStrategy resolutionStrategy,
            double confidence,
            List<String> evidence,
            List<String> warnings,
            String sourceFile,
            Integer lineNumber) {
    }
}
