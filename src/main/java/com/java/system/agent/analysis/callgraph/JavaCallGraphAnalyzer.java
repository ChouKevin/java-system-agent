package com.java.system.agent.analysis.callgraph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import com.java.system.agent.analysis.parser.ProjectParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.java.system.agent.analysis.model.FlattenedCallGraph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class JavaCallGraphAnalyzer {

    private final ProjectParserService projectParserService;
    private final ClassMetadataService classMetadataService;
    private final DtoAnalyzer dtoAnalyzer;
    private final CallGraphBuilder callGraphBuilder;
    private final int maxDepth;

    public JavaCallGraphAnalyzer(ProjectParserService projectParserService,
            ClassMetadataService classMetadataService,
            DtoAnalyzer dtoAnalyzer,
            CallGraphBuilder callGraphBuilder,
            @Value("${entry-point.call-graph-depth:5}") int maxDepth) {
        this.projectParserService = projectParserService;
        this.classMetadataService = classMetadataService;
        this.callGraphBuilder = callGraphBuilder;
        this.dtoAnalyzer = dtoAnalyzer;
        this.maxDepth = maxDepth;
    }

    /** 分析並扁平化 call graph — facade 層唯一入口 */
    public FlattenedCallGraph analyzeFlattened(Path repoRoot, String relativeFilePath, String methodName) {
        CallGraph callGraph = analyze(repoRoot, relativeFilePath, methodName);
        if (callGraph == null) {
            return FlattenedCallGraph.builder()
                    .methods(List.of())
                    .build();
        }
        return CallGraphVisitor.flattenToOptimized(callGraph, GraphVisitorConfig.defaultConfig());
    }

    /** 分析 call graph（回傳原始樹狀結構，供 callgraph 內部或測試使用） */
    CallGraph analyze(Path repoRoot, String relativeFilePath, String methodName) {
        log.info("Analyzing call graph for '{}' in '{}'", methodName, relativeFilePath);

        try {
            // 預熱：確保 parser + metadata cache 在 builder 遞迴前就位
            JavaParser javaParser = projectParserService.getOrCreateParser(repoRoot);
            classMetadataService.ensureInitialized(repoRoot);

            Path absoluteFilePath = repoRoot.resolve(relativeFilePath);
            CompilationUnit paramsCu = parseCompilationUnit(javaParser, absoluteFilePath);
            MethodDeclaration targetMethod = findTargetMethod(paramsCu, methodName, relativeFilePath);
            Map<String, String> dtoClasses = dtoAnalyzer.analyze(targetMethod);
            return callGraphBuilder.build(targetMethod, repoRoot, dtoClasses, maxDepth);
        } catch (IOException e) {
            log.error("Error reading source file: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Error analyzing call graph: {}", e.getMessage(), e);
            return null;
        }
    }

    private CompilationUnit parseCompilationUnit(JavaParser parser, Path absoluteFilePath)
            throws RuntimeException, IOException {
        return parser.parse(absoluteFilePath).getResult()
                .orElseThrow(() -> new RuntimeException("Failed to parse " + absoluteFilePath));
    }

    private MethodDeclaration findTargetMethod(CompilationUnit cu, String methodSignature, String relativeFilePath) {
        String methodName = extractSimpleMethodName(methodSignature);

        List<MethodDeclaration> candidates = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .toList();

        if (candidates.isEmpty()) {
            throw new RuntimeException("Method '" + methodName + "' not found in '" + relativeFilePath + "'");
        }

        if (candidates.size() == 1 || !methodSignature.contains("(")) {
            return candidates.get(0);
        }

        List<String> paramTypes = extractParamTypes(methodSignature);
        return candidates.stream()
                .filter(m -> matchesParams(m, paramTypes))
                .findFirst()
                .orElse(candidates.get(0));
    }

    private String extractSimpleMethodName(String methodSignature) {
        String trimmed = methodSignature.trim();
        int parenIdx = trimmed.indexOf('(');
        String beforeParen = parenIdx >= 0 ? trimmed.substring(0, parenIdx).trim() : trimmed;
        String[] parts = beforeParen.split("\\s+");
        return parts[parts.length - 1];
    }

    private List<String> extractParamTypes(String methodSignature) {
        int start = methodSignature.indexOf('(');
        int end = methodSignature.lastIndexOf(')');
        if (start < 0 || end <= start + 1) return List.of();

        String paramsStr = methodSignature.substring(start + 1, end).trim();
        if (paramsStr.isEmpty()) return List.of();

        return Arrays.stream(paramsStr.split(","))
                .map(p -> {
                    String[] parts = p.trim().split("\\s+");
                    return ScopeTypeResolver.simpleTypeName(parts[0]);
                })
                .toList();
    }

    private boolean matchesParams(MethodDeclaration method, List<String> paramTypes) {
        if (method.getParameters().size() != paramTypes.size()) return false;
        for (int i = 0; i < paramTypes.size(); i++) {
            String actual = ScopeTypeResolver.simpleTypeName(method.getParameter(i).getType().asString());
            if (!paramTypes.get(i).equals(actual)) return false;
        }
        return true;
    }

}
