package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.FlattenedMethodNode;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Consumer;

/** CallGraph 遍歷與扁平化工具 */
@Slf4j
public class CallGraphVisitor {

    /** 深度優先遍歷 */
    public static void visit(CallGraph root, Consumer<CallGraph> action) {
        if (root == null)
            return;
        action.accept(root);
        if (root.getCalledMethods() != null) {
            for (CallGraph child : root.getCalledMethods()) {
                visit(child, action);
            }
        }
    }

    /** 扁平化 call graph，去重並以 callee 列表取代巢狀結構以節省 token */
    public static FlattenedCallGraph flattenToOptimized(CallGraph root, GraphVisitorConfig config) {
        if (root == null) {
            return FlattenedCallGraph.builder()
                    .methods(Collections.emptyList())
                    .build();
        }

        FlattenContext ctx = new FlattenContext(config);
        ctx.collect(root, null);

        return FlattenedCallGraph.builder()
                .rootSignature(root.getSignature())
                .methods(ctx.orderedMethods)
                .relatedClasses(root.getRelatedClasses())
                .build();
    }

    private static class FlattenContext {
        final GraphVisitorConfig config;
        final List<FlattenedMethodNode> orderedMethods = new ArrayList<>();
        final Map<String, FlattenedMethodNode> indexBySignature = new HashMap<>();
        final Map<String, Set<String>> calleeDedup = new HashMap<>();

        FlattenContext(GraphVisitorConfig config) {
            this.config = config;
        }

        void collect(CallGraph node, String callerSignature) {
            if (node == null)
                return;

            String signature = node.getSignature();

            if (node.getCallType() == null) {
                return;
            }

            // Handle pass-through logic
            boolean isPassThrough = (config.getPassThroughTypes() != null
                    && config.getPassThroughTypes().contains(node.getCallType()))
                    || (CallType.INTERNAL_INTERFACE_DEFAULT.equals(node.getCallType())
                            && config.isKeepInterfaceDefaultMethods());

            if (isPassThrough) {
                if (node.getCalledMethods() != null) {
                    for (CallGraph child : node.getCalledMethods()) {
                        collect(child, callerSignature);
                    }
                }
                return;
            }

            // Filtering logic
            boolean shouldKeep = config.getKeepTypes() != null
                    && config.getKeepTypes().contains(node.getCallType());

            if (!shouldKeep) {
                return;
            }

            if (signature != null) {
                // 1. Add method details if not already present
                if (!indexBySignature.containsKey(signature)) {
                    String codeContent = null;
                    if (config.shouldIncludeCode(node.getCallType())) {
                        codeContent = node.getCode();
                    }

                    FlattenedMethodNode methodInfo = FlattenedMethodNode.builder()
                            .signature(node.getSignature())
                            .className(node.getClassName())
                            .methodName(node.getMethodName())
                            .callType(node.getCallType())
                            .desc(node.getDesc())
                            .code(codeContent)
                            .annotations(node.getAnnotations() != null ? new HashMap<>(node.getAnnotations()) : null)
                            .callees(new ArrayList<>())
                            .build();
                    indexBySignature.put(signature, methodInfo);
                    orderedMethods.add(methodInfo);
                } else {
                    FlattenedMethodNode existing = indexBySignature.get(signature);
                    String incomingContent = node.getCode();
                    if (existing.getCode() == null && incomingContent != null) {
                        if (config.shouldIncludeCode(node.getCallType())) {
                            existing.setCode(incomingContent);
                        }
                        existing.setDesc(node.getDesc());
                    }
                }

                // 2. Add callee relationship to parent (if any) — O(1) dedup via Set
                if (callerSignature != null) {
                    FlattenedMethodNode caller = indexBySignature.get(callerSignature);
                    if (caller != null) {
                        Set<String> seen = calleeDedup.computeIfAbsent(callerSignature, k -> new HashSet<>());
                        if (seen.add(signature)) {
                            List<String> callees = caller.getCallees();
                            if (callees == null) {
                                callees = new ArrayList<>();
                                caller.setCallees(callees);
                            }
                            callees.add(signature);
                        }
                    }
                }
            }

            // TRAVERSAL_CUTOFF：children 只取 signature 寫進 callees，不建立獨立 entry
            if (CallType.TRAVERSAL_CUTOFF.equals(node.getCallType())) {
                if (node.getCalledMethods() != null && signature != null) {
                    FlattenedMethodNode current = indexBySignature.get(signature);
                    if (current != null) {
                        Set<String> seen = calleeDedup.computeIfAbsent(signature, k -> new HashSet<>());
                        for (CallGraph child : node.getCalledMethods()) {
                            String childSig = child.getSignature();
                            if (childSig != null && seen.add(childSig)) {
                                current.getCallees().add(childSig);
                            }
                        }
                    }
                }
                return;
            }

            // Process children
            if (node.getCalledMethods() != null) {
                for (CallGraph child : node.getCalledMethods()) {
                    collect(child, signature);
                }
            }
        }
    }
}
