package com.java.system.agent.analysis.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.java.system.agent.analysis.model.ClassMetadata;
import com.java.system.agent.analysis.model.ResolutionStrategy;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.ScopeTypeResolver.ReceiverOrigin;
import com.java.system.agent.analysis.type.ScopeTypeResolver.ResolvedReceiver;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.*;

@Slf4j
@Component
public class CallGraphBuilder {

    private static final String RECEIVER_TYPE_INFERENCE_WARNING =
            "Receiver type inferred from parameter or local variable";
    private static final String NON_MYBATIS_DATA_ACCESS_WARNING =
            "Data access metadata detected without MyBatis mapper evidence";

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
            return withMethodLocation(node, method, ctx.repoRoot());
        }

        if (ctx.depth() >= ctx.maxDepth()) {
            // maxDepth 層：不遞迴，收集 callee leaf 節點存入 calledMethods 供 Visitor 提取 signature
            List<CallGraph> calleeLeaves = collectCalleeLeaves(methodCtx, ctx);
            return CallGraph.builder()
                    .signature(signature)
                    .className(className)
                    .packagePath(methodCtx.packagePath())
                    .methodName(currentMethodName)
                    .sourceFile(sourceFile(method, ctx.repoRoot()))
                    .startLine(startLine(method))
                    .endLine(endLine(method))
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
                .sourceFile(sourceFile(method, ctx.repoRoot()))
                .startLine(startLine(method))
                .endLine(endLine(method))
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
                String methodName = call.getNameAsString();
                Optional<ResolvedReceiver> receiverOpt = scopeTypeResolver.resolveReceiver(
                        call, methodCtx.method(), methodCtx.currentClass());

                if (receiverOpt.isEmpty()) {
                    CallGraph unresolved = CallGraph.leaf(null, null, methodName, CallType.UNRESOLVED, null);
                    unresolved.setResolutionEvidence(evidence(
                            ResolutionStrategy.UNRESOLVED,
                            0.10,
                            List.of("RECEIVER_TYPE_UNRESOLVED"),
                            List.of("Receiver type could not be resolved"),
                            call,
                            ctx.repoRoot()));
                    leaves.add(unresolved);
                    continue;
                }

                ResolvedReceiver receiver = receiverOpt.get();
                String targetClassName = receiver.typeName();

                Optional<ClassMetadata> metadataOpt = callerMetadata != null
                        ? classMetadataService.findClassMetadataByName(targetClassName, callerMetadata, ctx.repoRoot())
                        : classMetadataService.findClassMetadataByName(targetClassName, methodCtx.method(), ctx.repoRoot());
                if (metadataOpt.isEmpty()) {
                    CallGraph external = CallGraph.leaf(null, targetClassName, methodName, CallType.EXTERNAL_LIB, null);
                    external.setResolutionEvidence(evidence(
                            ResolutionStrategy.UNKNOWN,
                            0.50,
                            evidenceCodes(receiver, "TARGET_METADATA_MISSING"),
                            List.of("Target metadata could not be resolved"),
                            call,
                            ctx.repoRoot()));
                    leaves.add(external);
                    continue;
                }

                ClassMetadata metadata = metadataOpt.get();
                String sig = StringUtils.hasText(metadata.packageName())
                        ? metadata.packageName() + "." + metadata.className() + "#" + methodName
                        : metadata.className() + "#" + methodName;
                CallType type = classifier.detectType(metadata);
                CallGraph leaf = CallGraph.leaf(sig, metadata.className(), methodName, type, null);
                applyMetadataLocationIfAvailable(
                        leaf, metadata, methodName, call.getArguments().size(), ctx.repoRoot());
                List<String> warnings = new ArrayList<>(methodWarnings(receiver));
                warnings.add("Traversal cutoff prevented deeper analysis");
                leaf.setResolutionEvidence(evidence(
                        methodResolutionStrategy(receiver),
                        methodConfidence(receiver),
                        evidenceCodes(receiver, "TARGET_METADATA_FOUND", "TRAVERSAL_CUTOFF_CALLEE"),
                        warnings,
                        call,
                        ctx.repoRoot()));
                leaves.add(leaf);
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

    private CallGraph withEvidence(CallGraph node, CallResolutionEvidence evidence) {
        node.setResolutionEvidence(evidence);
        return node;
    }

    private CallGraph withMethodLocation(CallGraph node, MethodDeclaration method, Path repoRoot) {
        node.setSourceFile(sourceFile(method, repoRoot));
        node.setStartLine(startLine(method));
        node.setEndLine(endLine(method));
        return node;
    }

    private void applyMetadataLocationIfAvailable(CallGraph node, ClassMetadata metadata,
            String methodName, int paramCount, Path repoRoot) {
        node.setSourceFile(repoRelativePath(repoRoot, metadata.filePath()));
        findUniqueMethodSignature(metadata, methodName, paramCount)
                .ifPresent(methodSignature -> {
                    node.setStartLine(methodSignature.startLine());
                    node.setEndLine(methodSignature.endLine());
                });
    }

    private Optional<ClassMetadata.MethodSignature> findUniqueMethodSignature(
            ClassMetadata metadata, String methodName, int paramCount) {
        if (metadata.methods() == null) {
            return Optional.empty();
        }
        List<ClassMetadata.MethodSignature> matches = metadata.methods().stream()
                .filter(methodSignature -> methodSignature.name().equals(methodName))
                .filter(methodSignature -> methodSignature.paramCount() == paramCount)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private String sourceFile(MethodDeclaration method, Path repoRoot) {
        return method.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(storage -> repoRelativePath(repoRoot, storage.getPath()))
                .orElse(null);
    }

    private String sourceFile(MethodCallExpr call, Path repoRoot) {
        return call.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(storage -> repoRelativePath(repoRoot, storage.getPath()))
                .orElse(null);
    }

    private String repoRelativePath(Path repoRoot, Path sourcePath) {
        if (repoRoot == null || sourcePath == null) {
            return null;
        }
        Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
        Path normalizedSourcePath = sourcePath.toAbsolutePath().normalize();
        if (!normalizedSourcePath.startsWith(normalizedRepoRoot)) {
            return null;
        }
        return normalizedRepoRoot.relativize(normalizedSourcePath)
                .toString()
                .replace('\\', '/');
    }

    private Integer startLine(MethodDeclaration method) {
        return method.getBegin().map(position -> position.line).orElse(null);
    }

    private Integer endLine(MethodDeclaration method) {
        return method.getEnd().map(position -> position.line).orElse(null);
    }

    private CallResolutionEvidence evidence(
            ResolutionStrategy strategy,
            double confidence,
            List<String> evidence,
            List<String> warnings,
            MethodCallExpr call,
            Path repoRoot) {
        return new CallResolutionEvidence(
                strategy,
                confidence,
                List.copyOf(evidence),
                List.copyOf(warnings),
                sourceFile(call, repoRoot),
                call.getBegin().map(position -> position.line).orElse(null));
    }

    private List<String> evidenceCodes(ResolvedReceiver receiver, String... codes) {
        List<String> values = new ArrayList<>();
        if (receiver != null && StringUtils.hasText(receiver.evidenceCode())) {
            values.add(receiver.evidenceCode());
        }
        values.addAll(Arrays.asList(codes));
        return values;
    }

    private ResolutionStrategy methodResolutionStrategy(ResolvedReceiver receiver) {
        return switch (receiver.origin()) {
            case SAME_CLASS -> ResolutionStrategy.SAME_CLASS_METHOD;
            case STATIC_CLASS -> ResolutionStrategy.STATIC_METHOD;
            case FIELD -> ResolutionStrategy.SPRING_BEAN_BY_TYPE;
            case PARAMETER, LOCAL_VARIABLE -> ResolutionStrategy.HEURISTIC_NAME_MATCH;
            default -> ResolutionStrategy.HEURISTIC_NAME_MATCH;
        };
    }

    private double methodConfidence(ResolvedReceiver receiver) {
        return switch (receiver.origin()) {
            case SAME_CLASS, FIELD -> 0.95;
            case STATIC_CLASS -> 0.90;
            case PARAMETER, LOCAL_VARIABLE -> 0.65;
            default -> 0.70;
        };
    }

    private List<String> methodWarnings(ResolvedReceiver receiver) {
        return switch (receiver.origin()) {
            case PARAMETER, LOCAL_VARIABLE -> List.of(RECEIVER_TYPE_INFERENCE_WARNING);
            default -> List.of();
        };
    }

    private void processMethodCall(MethodCallExpr call, TraversalCtx ctx, List<CallGraph> children,
            MethodDeclaration currentMethod, ClassOrInterfaceDeclaration currentClass,
            ClassMetadata callerMetadata) {

        Optional<ResolvedReceiver> receiverOpt = scopeTypeResolver.resolveReceiver(call, currentMethod, currentClass);

        if (receiverOpt.isEmpty()) {
            CallGraph unresolved = CallGraph.leaf(null, null, call.getNameAsString(),
                    CallType.UNRESOLVED, null);
            children.add(withEvidence(unresolved, evidence(
                    ResolutionStrategy.UNRESOLVED,
                    0.10,
                    List.of("RECEIVER_TYPE_UNRESOLVED"),
                    List.of("Receiver type could not be resolved"),
                    call,
                    ctx.repoRoot())));
            return;
        }

        ResolvedReceiver receiver = receiverOpt.get();
        String targetClassName = receiver.typeName();
        String methodName = call.getNameAsString();
        int argCount = call.getArguments().size();

        // 優先用 metadata-based lookup（不 parse file），fallback 到 AST-based
        Optional<ClassMetadata> metadataOpt = callerMetadata != null
                ? classMetadataService.findClassMetadataByName(targetClassName, callerMetadata, ctx.repoRoot())
                : classMetadataService.findClassMetadataByName(targetClassName, currentMethod, ctx.repoRoot());

        if (metadataOpt.isEmpty()) {
            CallGraph external = CallGraph.leaf(null, targetClassName, methodName,
                    CallType.EXTERNAL_LIB, null);
            children.add(withEvidence(external, evidence(
                    ResolutionStrategy.UNKNOWN,
                    0.50,
                    evidenceCodes(receiver, "TARGET_METADATA_MISSING"),
                    List.of("Target metadata could not be resolved"),
                    call,
                    ctx.repoRoot())));
            return;
        }

        ClassMetadata metadata = metadataOpt.get();

        String signature = StringUtils.hasText(metadata.packageName())
                ? metadata.packageName() + "." + metadata.className() + "#" + methodName
                : metadata.className() + "#" + methodName;

        if (classifier.isDatabaseLayer(metadata)) {
            DataAccessResolution dataAccess = buildDataAccessNode(
                    signature,
                    metadata,
                    methodName,
                    argCount,
                    ctx.repoRoot());
            boolean hasMapperAnnotation = hasMapperAnnotation(metadata);
            boolean hasMyBatisPlusDataAccess = isMyBatisPlusDataAccess(metadata);
            List<String> dataAccessEvidence = evidenceCodes(
                    receiver,
                    "TARGET_METADATA_FOUND",
                    hasMapperAnnotation
                            ? "MYBATIS_MAPPER_ANNOTATION"
                            : "DATA_ACCESS_METADATA_FOUND");
            if ("MYBATIS_XML_SQL_FOUND".equals(dataAccess.sqlEvidenceCode())) {
                dataAccessEvidence = new ArrayList<>(dataAccessEvidence);
                dataAccessEvidence.add("MYBATIS_XML_SQL_FOUND");
            }
            if ("MYBATIS_ANNOTATION_SQL_FOUND".equals(dataAccess.sqlEvidenceCode())) {
                dataAccessEvidence = new ArrayList<>(dataAccessEvidence);
                dataAccessEvidence.add("MYBATIS_ANNOTATION_SQL_FOUND");
            }
            if (hasMyBatisPlusDataAccess) {
                dataAccessEvidence = new ArrayList<>(dataAccessEvidence);
                dataAccessEvidence.add("MYBATIS_PLUS_BASE_MAPPER");
            }
            boolean hasMyBatisEvidence = hasMapperAnnotation
                    || "MYBATIS_XML_SQL_FOUND".equals(dataAccess.sqlEvidenceCode())
                    || "MYBATIS_ANNOTATION_SQL_FOUND".equals(dataAccess.sqlEvidenceCode())
                    || hasMyBatisPlusDataAccess;
            children.add(withEvidence(dataAccess.node(), evidence(
                    hasMyBatisEvidence ? ResolutionStrategy.MYBATIS_MAPPER : ResolutionStrategy.UNKNOWN,
                    hasMyBatisEvidence ? 0.95 : 0.70,
                    dataAccessEvidence,
                    hasMyBatisEvidence ? List.of() : List.of(NON_MYBATIS_DATA_ACCESS_WARNING),
                    call,
                    ctx.repoRoot())));
            return;
        }

        if (classifier.isImplOfMyBatis(metadata)) {
            boolean isCustomMethod = metadata.methods() != null && metadata.methods().stream()
                    .anyMatch(m -> m.name().equals(methodName) && m.paramCount() == argCount);
            if (!isCustomMethod) {
                CallGraph inherited = CallGraph.leaf(signature, metadata.className(), methodName,
                        CallType.DATA_ACCESS, "Inherited MyBatis Service Method (Database Layer)");
                children.add(withEvidence(inherited, evidence(
                        ResolutionStrategy.MYBATIS_MAPPER,
                        0.85,
                        evidenceCodes(receiver, "TARGET_METADATA_FOUND", "MYBATIS_PLUS_INHERITED_METHOD"),
                        List.of(),
                        call,
                        ctx.repoRoot())));
                return;
            }
        }

        if (classifier.isLombokGenerated(metadata, methodName)) {
            CallGraph generated = CallGraph.leaf(signature, metadata.className(), methodName,
                    CallType.GENERATED_CODE, null);
            children.add(withEvidence(generated, evidence(
                    ResolutionStrategy.UNKNOWN,
                    0.80,
                    evidenceCodes(receiver, "LOMBOK_GENERATED_METHOD"),
                    List.of(),
                    call,
                    ctx.repoRoot())));
            return;
        }

        // 工具類的 static method 會在這被過濾，未來可能加白名單
        if (!classifier.shouldRecurse(metadata) && !ReceiverOrigin.STATIC_CLASS.equals(receiver.origin())) {
            CallGraph filtered = CallGraph.leaf(signature, metadata.className(), methodName,
                    CallType.INTERNAL_CLASS, "Filtered (Not a Bean/Business Component)");
            applyMetadataLocationIfAvailable(filtered, metadata, methodName, argCount, ctx.repoRoot());
            children.add(withEvidence(filtered, evidence(
                    ResolutionStrategy.HEURISTIC_NAME_MATCH,
                    0.60,
                    evidenceCodes(receiver, "TARGET_METADATA_FOUND"),
                    List.of("Target class is not a traversable Spring component"),
                    call,
                    ctx.repoRoot())));
            return;
        }

        Optional<ClassOrInterfaceDeclaration> typeAstOpt = classMetadataService.resolveToAST(metadata, ctx.repoRoot());

        if (typeAstOpt.isEmpty()) {
            CallGraph parseFailed = CallGraph.leaf(signature, metadata.className(), methodName,
                    CallType.UNRESOLVED, "Source code parse failed");
            children.add(withEvidence(parseFailed, evidence(
                    ResolutionStrategy.UNRESOLVED,
                    0.20,
                    evidenceCodes(receiver, "SOURCE_PARSE_FAILED"),
                    List.of("Source code parse failed"),
                    call,
                    ctx.repoRoot())));
            return;
        }

        processResolvedType(typeAstOpt.get(), metadata, methodName, argCount, signature, receiver, call, ctx, children);
    }

    private DataAccessResolution buildDataAccessNode(String signature, ClassMetadata metadata,
            String methodName, int paramCount, Path repoRoot) {
        Optional<ClassMetadata.MethodSignature> methodSignatureOpt =
                findUniqueMethodSignature(metadata, methodName, paramCount);
        Optional<String> xmlSqlOpt = classMetadataService.findMapperXmlSql(metadata, methodName, repoRoot);
        String annotationSql = methodSignatureOpt
                .filter(this::hasMyBatisSqlAnnotation)
                .map(ClassMetadata.MethodSignature::sql)
                .filter(StringUtils::hasText)
                .orElse(null);
        String xmlSql = xmlSqlOpt.filter(StringUtils::hasText).orElse(null);
        String sql = StringUtils.hasText(xmlSql) ? xmlSql : annotationSql;
        String sqlEvidenceCode = null;
        if (StringUtils.hasText(xmlSql)) {
            sqlEvidenceCode = "MYBATIS_XML_SQL_FOUND";
        } else if (StringUtils.hasText(annotationSql)) {
            sqlEvidenceCode = "MYBATIS_ANNOTATION_SQL_FOUND";
        }

        CallGraph node = CallGraph.leafWithCode(signature, metadata.className(), methodName,
                CallType.DATA_ACCESS, null, sql);
        applyMetadataLocationIfAvailable(node, metadata, methodName, paramCount, repoRoot);
        return new DataAccessResolution(node, sqlEvidenceCode);
    }

    private boolean hasMapperAnnotation(ClassMetadata metadata) {
        if (metadata.annotations() == null) {
            return false;
        }
        return metadata.annotations().stream()
                .anyMatch(annotation -> "Mapper".equals(annotation)
                        || "org.apache.ibatis.annotations.Mapper".equals(annotation)
                        || annotation.endsWith(".Mapper"));
    }

    private boolean isMyBatisPlusDataAccess(ClassMetadata metadata) {
        return metadata.implementedTypes().stream()
                .anyMatch(type -> type.contains("BaseMapper"))
                || metadata.extendedTypes().stream()
                .anyMatch(type -> type.contains("BaseMapper"))
                || classifier.isImplOfMyBatis(metadata);
    }

    private boolean hasMyBatisSqlAnnotation(ClassMetadata.MethodSignature methodSignature) {
        if (methodSignature.annotations() == null) {
            return false;
        }
        return methodSignature.annotations().stream()
                .anyMatch(annotation -> "Select".equals(annotation)
                        || "Update".equals(annotation)
                        || "Insert".equals(annotation)
                        || "Delete".equals(annotation)
                        || annotation.endsWith(".Select")
                        || annotation.endsWith(".Update")
                        || annotation.endsWith(".Insert")
                        || annotation.endsWith(".Delete"));
    }

    private record DataAccessResolution(
            CallGraph node,
            String sqlEvidenceCode) {
    }

    /** Fallback when AST method declaration is not found in the resolved class. */
    private CallGraph buildMethodNotFoundNode(String signature, ClassMetadata metadata, String methodName,
            ResolvedReceiver receiver, MethodCallExpr call, Path repoRoot) {
        if (classifier.isImplOfMyBatis(metadata)) {
            return withEvidence(CallGraph.leaf(signature, metadata.className(), methodName,
                    CallType.DATA_ACCESS, "Inherited MyBatis Service Method (Database Layer)"), evidence(
                    ResolutionStrategy.MYBATIS_MAPPER,
                    0.85,
                    evidenceCodes(receiver, "TARGET_METADATA_FOUND", "MYBATIS_PLUS_INHERITED_METHOD"),
                    List.of(),
                    call,
                    repoRoot));
        }
        if (classifier.isLombokGenerated(metadata, methodName)) {
            return withEvidence(CallGraph.leaf(signature, metadata.className(), methodName,
                    CallType.GENERATED_CODE, null), evidence(
                    ResolutionStrategy.UNKNOWN,
                    0.80,
                    evidenceCodes(receiver, "LOMBOK_GENERATED_METHOD"),
                    List.of(),
                    call,
                    repoRoot));
        }
        CallType type = classifier.detectType(metadata);
        String desc = classifier.shouldRecurse(metadata)
                ? "Method source not found in class"
                : "Filtered (Not a Bean/Business Component)";
        List<String> warnings = classifier.shouldRecurse(metadata)
                ? List.of("Method source not found in class")
                : List.of("Target class is not a traversable Spring component");
        return withEvidence(CallGraph.leaf(signature, metadata.className(), methodName, type, desc), evidence(
                classifier.shouldRecurse(metadata)
                        ? ResolutionStrategy.UNRESOLVED
                        : ResolutionStrategy.HEURISTIC_NAME_MATCH,
                classifier.shouldRecurse(metadata) ? 0.35 : 0.60,
                evidenceCodes(receiver, "TARGET_METADATA_FOUND", "METHOD_SOURCE_NOT_FOUND"),
                warnings,
                call,
                repoRoot));
    }

    private void processResolvedType(ClassOrInterfaceDeclaration typeAst, ClassMetadata metadata,
            String methodName, int paramCount, String qualifiedSignature, ResolvedReceiver receiver, MethodCallExpr call,
            TraversalCtx ctx, List<CallGraph> children) {

        String className = metadata.className();

        if (metadata.isInterface()) {
            List<ImplResult> implementations = findImplementationsByName(className, methodName, paramCount, ctx);

            CallGraph interfaceNode = CallGraph.branch(qualifiedSignature, className, methodName, CallType.INTERFACE);
            applyMetadataLocationIfAvailable(interfaceNode, metadata, methodName, paramCount, ctx.repoRoot());
            if (implementations.isEmpty()) {
                interfaceNode.setResolutionEvidence(evidence(
                        ResolutionStrategy.UNRESOLVED,
                        0.20,
                        evidenceCodes(receiver, "TARGET_METADATA_FOUND", "METHOD_SOURCE_NOT_FOUND"),
                        List.of("No interface implementation matched"),
                        call,
                        ctx.repoRoot()));
                children.add(interfaceNode);
                return;
            }

            boolean hasMultipleImplementations = implementations.size() > 1;
            ResolutionStrategy strategy = hasMultipleImplementations
                    ? ResolutionStrategy.INTERFACE_MULTI_IMPL
                    : ResolutionStrategy.INTERFACE_SINGLE_IMPL;
            double confidence = hasMultipleImplementations ? 0.60 : 0.90;
            List<String> interfaceEvidence = evidenceCodes(
                    receiver,
                    hasMultipleImplementations
                            ? "MULTIPLE_INTERFACE_IMPLEMENTATIONS"
                            : "SINGLE_INTERFACE_IMPLEMENTATION",
                    "TARGET_METHOD_FOUND");
            List<String> warnings = hasMultipleImplementations
                    ? List.of("Multiple interface implementations matched")
                    : List.of();
            interfaceNode.setResolutionEvidence(evidence(
                    strategy,
                    confidence,
                    interfaceEvidence,
                    warnings,
                    call,
                    ctx.repoRoot()));

            for (ImplResult implResult : implementations) {
                CallGraph implGraph = buildGraph(MethodCtx.of(implResult.method()), ctx);
                implGraph.setResolutionEvidence(evidence(
                        strategy,
                        confidence,
                        interfaceEvidence,
                        warnings,
                        call,
                        ctx.repoRoot()));

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
                CallGraph resolvedGraph = buildGraph(MethodCtx.of(deckOpt.get()), ctx);
                children.add(withEvidence(resolvedGraph, evidence(
                        methodResolutionStrategy(receiver),
                        methodConfidence(receiver),
                        evidenceCodes(receiver, "TARGET_METADATA_FOUND", "TARGET_METHOD_FOUND"),
                        methodWarnings(receiver),
                        call,
                        ctx.repoRoot())));
            } else {
                children.add(buildMethodNotFoundNode(
                        qualifiedSignature, metadata, methodName, receiver, call, ctx.repoRoot()));
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
            boolean hasMethod = metadata.methods() != null && metadata.methods().stream()
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
