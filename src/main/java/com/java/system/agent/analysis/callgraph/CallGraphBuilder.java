package com.java.system.agent.analysis.callgraph;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.java.system.agent.analysis.model.ClassMetadata;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.*;

@Slf4j
@Component
public class CallGraphBuilder {

    private final CallGraphClassifier classifier;
    private final ClassMetadataService classMetadataService;
    private final ScopeTypeResolver scopeTypeResolver;

    public CallGraphBuilder(CallGraphClassifier classifier,
            ClassMetadataService classMetadataService, ScopeTypeResolver scopeTypeResolver) {
        this.classifier = classifier;
        this.classMetadataService = classMetadataService;
        this.scopeTypeResolver = scopeTypeResolver;
    }

    public CallGraph build(MethodDeclaration method, Path repoRoot,
            Map<String, String> rootRelatedClasses, int maxDepth) {
        TraversalCtx ctx = new TraversalCtx(repoRoot, 0, maxDepth, new HashSet<>());
        CallGraph graph = buildGraph(MethodCtx.of(method), ctx);
        graph.setRelatedClasses(rootRelatedClasses);
        return graph;
    }

    private CallGraph buildGraph(MethodCtx methodCtx, TraversalCtx ctx) {
        MethodDeclaration method = methodCtx.method();
        String signature = methodCtx.signature();
        String className = methodCtx.className();
        String currentMethodName = methodCtx.methodName();

        if (ctx.visited().contains(signature)) {
            CallGraph node = CallGraph.leaf(signature, className, currentMethodName, CallType.CIRCULAR_REF, null);
            node.setPackagePath(methodCtx.packagePath());
            return node;
        }

        if (ctx.depth() >= ctx.maxDepth()) {
            // maxDepth 層：不遞迴，收集 callee leaf 節點存入 calledMethods 供 Visitor 提取 signature
            List<CallGraph> calleeLeaves = collectCalleeLeaves(methodCtx, ctx);
            return CallGraph.builder()
                    .signature(signature)
                    .className(className)
                    .packagePath(methodCtx.packagePath())
                    .methodName(currentMethodName)
                    .callType(CallType.TRAVERSAL_CUTOFF)
                    .code(method.toString())
                    .desc(method.getJavadoc().map(d -> d.getDescription().toText()).orElse(null))
                    .annotations(methodCtx.annotations())
                    .calledMethods(calleeLeaves)
                    .build();
        }

        ctx.visited().add(signature);

        // 取得 caller metadata（cache lookup，不 parse file）
        ClassMetadata callerMetadata = classMetadataService.findClassMetadata(
                ctx.repoRoot(), className, methodCtx.packagePath())
                .orElse(null);

        List<CallGraph> children = new ArrayList<>();
        for (MethodCallExpr call : methodCtx.calls()) {
            try {
                processMethodCall(call, ctx.deeper(), children, method, methodCtx.currentClass(), callerMetadata);
            } catch (Exception e) {
                log.warn("Could not resolve call {}: {}", call.getNameAsString(), e.getMessage(), e);
            }
        }

        // method-level 優先（@Scheduled, @RabbitListener 等），沒有才 fallback class-level
        CallType type = classifier.detectMethodType(method);
        if (type == null) {
            type = callerMetadata != null
                    ? classifier.detectType(callerMetadata)
                    : detectTypeFromAST(method);
        }

        String sql = null;
        if (type == CallType.DATA_ACCESS && callerMetadata != null) {
            sql = classMetadataService.findMapperXmlSql(callerMetadata, currentMethodName, ctx.repoRoot())
                    .orElse(null);
        }

        CallGraph.CallGraphBuilder builder = CallGraph.builder()
                .signature(signature)
                .className(className)
                .packagePath(methodCtx.packagePath())
                .methodName(currentMethodName)
                .callType(type)
                .code(sql != null ? sql : method.toString())
                .desc(method.getJavadoc().map(d -> d.getDescription().toText()).orElse(null))
                .annotations(methodCtx.annotations())
                .calledMethods(children);

        return builder.build();
    }

    /**
     * maxDepth 層：不遞迴展開，僅收集 callee 的 leaf 節點（帶 signature + callType）。
     * 存入 calledMethods，由 Visitor 提取 signature 寫進 callees。
     */
    private List<CallGraph> collectCalleeLeaves(MethodCtx methodCtx, TraversalCtx ctx) {
        List<CallGraph> leaves = new ArrayList<>();
        ClassMetadata callerMetadata = classMetadataService.findClassMetadata(
                ctx.repoRoot(), methodCtx.className(), methodCtx.packagePath())
                .orElse(null);

        for (MethodCallExpr call : methodCtx.calls()) {
            try {
                Optional<String> typeNameOpt = scopeTypeResolver.inferTypeName(
                        call, methodCtx.method(), methodCtx.currentClass());
                if (typeNameOpt.isEmpty()) continue;

                String targetClassName = typeNameOpt.get();
                String methodName = call.getNameAsString();

                Optional<ClassMetadata> metadataOpt = callerMetadata != null
                        ? classMetadataService.findClassMetadataByName(targetClassName, callerMetadata, ctx.repoRoot())
                        : classMetadataService.findClassMetadataByName(targetClassName, methodCtx.method(), ctx.repoRoot());
                if (metadataOpt.isEmpty()) continue;

                ClassMetadata metadata = metadataOpt.get();
                String sig = StringUtils.hasText(metadata.packageName())
                        ? metadata.packageName() + "." + metadata.className() + "#" + methodName
                        : metadata.className() + "#" + methodName;
                CallType type = classifier.detectType(metadata);
                leaves.add(CallGraph.leaf(sig, metadata.className(), methodName, type, null));
            } catch (Exception e) {
                log.warn("Could not resolve callee for {}: {}", call.getNameAsString(), e.getMessage());
            }
        }
        return leaves;
    }

    /** AST fallback — 僅用於 root method 沒有 metadata 的場景 */
    private CallType detectTypeFromAST(MethodDeclaration method) {
        if (method.getParentNode().isPresent()
                && method.getParentNode().get() instanceof ClassOrInterfaceDeclaration cls) {
            return classifier.detectType(cls);
        }
        return CallType.INTERNAL_CLASS;
    }

    private void processMethodCall(MethodCallExpr call, TraversalCtx ctx, List<CallGraph> children,
            MethodDeclaration currentMethod, ClassOrInterfaceDeclaration currentClass,
            ClassMetadata callerMetadata) {

        Optional<String> typeNameOpt = scopeTypeResolver.inferTypeName(call, currentMethod, currentClass);

        if (typeNameOpt.isEmpty()) {
            children.add(CallGraph.leaf(null, null, call.getNameAsString(),
                    CallType.UNRESOLVED, null));
            return;
        }

        String targetClassName = typeNameOpt.get();
        String methodName = call.getNameAsString();
        int argCount = call.getArguments().size();

        // 優先用 metadata-based lookup（不 parse file），fallback 到 AST-based
        Optional<ClassMetadata> metadataOpt = callerMetadata != null
                ? classMetadataService.findClassMetadataByName(targetClassName, callerMetadata, ctx.repoRoot())
                : classMetadataService.findClassMetadataByName(targetClassName, currentMethod, ctx.repoRoot());

        if (metadataOpt.isEmpty()) {
            children.add(CallGraph.leaf(null, null, methodName,
                    CallType.EXTERNAL_LIB, null));
            return;
        }

        ClassMetadata metadata = metadataOpt.get();

        String signature = StringUtils.hasText(metadata.packageName())
                ? metadata.packageName() + "." + metadata.className() + "#" + methodName
                : metadata.className() + "#" + methodName;

        if (classifier.isDatabaseLayer(metadata)) {
            children.add(buildDataAccessNode(signature, metadata, methodName, argCount, ctx.repoRoot()));
            return;
        }

        if (classifier.isImplOfMyBatis(metadata)) {
            boolean isCustomMethod = metadata.methods() != null && metadata.methods().stream()
                    .anyMatch(m -> m.name().equals(methodName) && m.paramCount() == argCount);
            if (!isCustomMethod) {
                children.add(CallGraph.leaf(signature, metadata.className(), methodName,
                        CallType.DATA_ACCESS, "Inherited MyBatis Service Method (Database Layer)"));
                return;
            }
        }

        if (classifier.isLombokGenerated(metadata, methodName)) {
            children.add(CallGraph.leaf(signature, metadata.className(), methodName,
                    CallType.GENERATED_CODE, null));
            return;
        }

        // 工具類的 static method 會在這被過濾，未來可能加白名單
        if (!classifier.shouldRecurse(metadata)) {
            children.add(CallGraph.leaf(signature, metadata.className(), methodName,
                    CallType.INTERNAL_CLASS, "Filtered (Not a Bean/Business Component)"));
            return;
        }

        Optional<ClassOrInterfaceDeclaration> typeAstOpt = classMetadataService.resolveToAST(metadata, ctx.repoRoot());

        if (typeAstOpt.isEmpty()) {
            children.add(CallGraph.leaf(signature, metadata.className(), methodName,
                    CallType.UNRESOLVED, "Source code parse failed"));
            return;
        }

        processResolvedType(typeAstOpt.get(), metadata, methodName, argCount, signature, ctx, children);
    }

    private CallGraph buildDataAccessNode(String signature, ClassMetadata metadata,
            String methodName, int paramCount, Path repoRoot) {
        String sql = metadata.methods() == null ? null
                : metadata.methods().stream()
                        .filter(m -> m.name().equals(methodName) && m.paramCount() == paramCount)
                        .map(ClassMetadata.MethodSignature::sql)
                        .findFirst()
                        .orElse(null);

        if (sql == null) {
            sql = classMetadataService.findMapperXmlSql(metadata, methodName, repoRoot).orElse(null);
        }

        return CallGraph.leafWithCode(signature, metadata.className(), methodName,
                CallType.DATA_ACCESS, null, sql);
    }

    /** Fallback when AST method declaration is not found in the resolved class. */
    private CallGraph buildMethodNotFoundNode(String signature, ClassMetadata metadata, String methodName) {
        if (classifier.isImplOfMyBatis(metadata)) {
            return CallGraph.leaf(signature, metadata.className(), methodName,
                    CallType.DATA_ACCESS, "Inherited MyBatis Service Method (Database Layer)");
        }
        if (classifier.isLombokGenerated(metadata, methodName)) {
            return CallGraph.leaf(signature, metadata.className(), methodName,
                    CallType.GENERATED_CODE, null);
        }
        CallType type = classifier.detectType(metadata);
        String desc = classifier.shouldRecurse(metadata)
                ? "Method source not found in class"
                : "Filtered (Not a Bean/Business Component)";
        return CallGraph.leaf(signature, metadata.className(), methodName, type, desc);
    }

    private void processResolvedType(ClassOrInterfaceDeclaration typeAst, ClassMetadata metadata,
            String methodName, int paramCount, String qualifiedSignature,
            TraversalCtx ctx, List<CallGraph> children) {

        String className = metadata.className();

        if (metadata.isInterface()) {
            List<ImplResult> implementations = findImplementationsByName(className, methodName, paramCount, ctx);

            CallGraph interfaceNode = CallGraph.branch(qualifiedSignature, className, methodName, CallType.INTERFACE);

            for (ImplResult implResult : implementations) {
                CallGraph implGraph = buildGraph(MethodCtx.of(implResult.method()), ctx);

                if (!implResult.metadata().profiles().isEmpty()) {
                    String profileInfo = "[Profiles: " + String.join(", ", implResult.metadata().profiles()) + "]";
                    implGraph.setDesc(implGraph.getDesc() == null ? profileInfo : implGraph.getDesc() + " " + profileInfo);
                }

                interfaceNode.getCalledMethods().add(implGraph);
            }
            children.add(interfaceNode);

        } else {
            Optional<MethodDeclaration> deckOpt = findMethodInClass(typeAst, methodName, paramCount);

            if (deckOpt.isPresent()) {
                children.add(buildGraph(MethodCtx.of(deckOpt.get()), ctx));
            } else {
                children.add(buildMethodNotFoundNode(qualifiedSignature, metadata, methodName));
            }
        }
    }

    private Optional<MethodDeclaration> findMethodInClass(ClassOrInterfaceDeclaration typeAst,
            String methodName, int paramCount) {
        return typeAst.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .filter(m -> m.getParameters().size() == paramCount)
                .findFirst();
    }

    private record ImplResult(MethodDeclaration method, ClassMetadata metadata) {
    }

    private List<ImplResult> findImplementationsByName(String interfaceName, String methodName,
            int paramCount, TraversalCtx ctx) {
        List<ImplResult> impls = new ArrayList<>();

        List<ClassMetadata> candidates = classMetadataService.findImplementingClasses(
                ctx.repoRoot(), interfaceName);

        for (ClassMetadata metadata : candidates) {
            // 不用 shouldRecurse 過濾 — interface 的實作本身就是 call graph 需要的，
            // 即使是純 Java class（strategy pattern、domain model 等）也應納入分析
            boolean hasMethod = metadata.methods().stream()
                    .anyMatch(m -> m.name().equals(methodName) && m.paramCount() == paramCount);

            if (!hasMethod) {
                continue;
            }

            classMetadataService.resolveToAST(metadata, ctx.repoRoot())
                    .ifPresent(cls -> {
                        cls.getMethods().stream()
                                .filter(m -> m.getNameAsString().equals(methodName)
                                        && m.getParameters().size() == paramCount)
                                .findFirst()
                                .ifPresent(m -> impls.add(new ImplResult(m, metadata)));
                    });
        }
        return impls;
    }
}
