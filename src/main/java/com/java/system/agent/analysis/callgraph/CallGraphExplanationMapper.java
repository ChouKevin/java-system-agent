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
                    null,
                    resolution.resolutionStrategy(),
                    resolution.confidence(),
                    resolution.warnings()));
            collect(repoId, child, nodesByMethodId, edges);
        }
    }

    private CallNode toCallNode(MethodId methodId, CallGraph node) {
        return new CallNode(
                methodId,
                node.getSignature(),
                node.getCallType(),
                null,
                null,
                null,
                immutableMap(node.getAnnotations()),
                node.getCode());
    }

    private MethodId toMethodId(String repoId, CallGraph node) {
        ParsedSignature parsedSignature = parseSignature(node.getSignature());
        String packageName = parsedSignature.packageName();
        String className = parsedSignature.className();
        String methodName = parsedSignature.methodName();

        if (!StringUtils.hasText(packageName)) {
            packageName = node.getPackagePath();
        }
        if (!StringUtils.hasText(className)) {
            className = node.getClassName();
        }
        if (!StringUtils.hasText(methodName)) {
            methodName = node.getMethodName();
        }

        return new MethodId(repoId, packageName, className, methodName, List.of());
    }

    private ParsedSignature parseSignature(String signature) {
        if (!StringUtils.hasText(signature)) {
            return new ParsedSignature(null, null, null);
        }

        int separatorIndex = signature.indexOf('#');
        if (separatorIndex < 0) {
            return new ParsedSignature(null, null, null);
        }

        String owner = signature.substring(0, separatorIndex);
        String declaration = signature.substring(separatorIndex + 1);
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(declaration)) {
            return new ParsedSignature(null, null, null);
        }

        int ownerClassSeparator = owner.lastIndexOf('.');
        String packageName = ownerClassSeparator >= 0 ? owner.substring(0, ownerClassSeparator) : null;
        String className = ownerClassSeparator >= 0 ? owner.substring(ownerClassSeparator + 1) : owner;
        String methodName = parseMethodName(declaration);

        return new ParsedSignature(packageName, className, methodName);
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

    private Resolution resolve(CallGraph caller, CallGraph callee) {
        if (CallType.UNRESOLVED.equals(callee.getCallType())) {
            return new Resolution(
                    ResolutionStrategy.UNRESOLVED,
                    UNRESOLVED_CONFIDENCE,
                    List.of("Call could not be resolved"));
        }
        if (CallType.DATA_ACCESS.equals(callee.getCallType())) {
            return new Resolution(ResolutionStrategy.MYBATIS_MAPPER, DATA_ACCESS_CONFIDENCE, List.of());
        }
        if (CallType.INTERFACE.equals(caller.getCallType())) {
            int implementationCount = CollectionUtils.isEmpty(caller.getCalledMethods())
                    ? 0
                    : caller.getCalledMethods().size();
            if (implementationCount == 1) {
                return new Resolution(
                        ResolutionStrategy.INTERFACE_SINGLE_IMPL,
                        INTERFACE_SINGLE_IMPL_CONFIDENCE,
                        List.of());
            }
            if (implementationCount > 1) {
                return new Resolution(
                        ResolutionStrategy.INTERFACE_MULTI_IMPL,
                        INTERFACE_MULTI_IMPL_CONFIDENCE,
                        List.of("Multiple interface implementations matched"));
            }
        }
        if (CallType.EXTERNAL_LIB.equals(callee.getCallType())) {
            return new Resolution(ResolutionStrategy.UNKNOWN, EXTERNAL_LIB_CONFIDENCE, List.of());
        }
        return new Resolution(ResolutionStrategy.HEURISTIC_NAME_MATCH, HEURISTIC_CONFIDENCE, List.of());
    }

    private Map<String, String> immutableMap(Map<String, String> values) {
        if (CollectionUtils.isEmpty(values)) {
            return Map.of();
        }
        return Map.copyOf(values);
    }

    private record ParsedSignature(String packageName, String className, String methodName) {
    }

    private record Resolution(
            ResolutionStrategy resolutionStrategy,
            double confidence,
            List<String> warnings) {
    }
}
