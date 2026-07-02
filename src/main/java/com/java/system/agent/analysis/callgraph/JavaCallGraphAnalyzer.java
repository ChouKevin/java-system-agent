package com.java.system.agent.analysis.callgraph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.java.system.agent.analysis.model.AnalysisErrorCode;
import com.java.system.agent.analysis.model.AnalysisMetadata;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisWarning;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    public FlattenedCallGraph analyzeFlattened(Path repoRoot, String relativeFilePath, String methodName) {
        CallGraph callGraph = analyze(repoRoot, relativeFilePath, methodName);
        if (callGraph == null) {
            return FlattenedCallGraph.builder()
                    .methods(List.of())
                    .build();
        }
        return CallGraphVisitor.flattenToOptimized(callGraph, GraphVisitorConfig.defaultConfig());
    }

    public AnalysisResult<FlattenedCallGraph> analyzeFlattenedResult(
            Path repoRoot,
            String relativeFilePath,
            String methodName,
            AnalysisMetadata metadata) {
        try {
            CallGraph callGraph = analyzeOrThrow(repoRoot, relativeFilePath, methodName);
            FlattenedCallGraph flattened = callGraph == null
                    ? FlattenedCallGraph.builder().methods(List.of()).build()
                    : CallGraphVisitor.flattenToOptimized(callGraph, GraphVisitorConfig.defaultConfig());
            List<AnalysisWarning> warnings = unresolvedWarnings(flattened);
            if (!warnings.isEmpty()) {
                return AnalysisResult.partial(flattened, warnings, List.of(), metadata);
            }
            return AnalysisResult.success(flattened, metadata);
        } catch (IOException e) {
            log.error("Error reading source file: {}", e.getMessage(), e);
            return AnalysisResult.failed(
                    AnalysisErrorCode.PARSE_FAILED,
                    "Failed to read or parse source file",
                    e.getMessage(),
                    metadata);
        } catch (RuntimeException e) {
            log.error("Error analyzing call graph: {}", e.getMessage(), e);
            AnalysisErrorCode code = classifyRuntimeFailure(e);
            return AnalysisResult.failed(code, messageFor(code), e.getMessage(), metadata);
        } catch (Exception e) {
            log.error("Unexpected error analyzing call graph: {}", e.getMessage(), e);
            return AnalysisResult.failed(
                    AnalysisErrorCode.INTERNAL_ERROR,
                    "Unexpected analysis failure",
                    e.getMessage(),
                    metadata);
        }
    }

    CallGraph analyze(Path repoRoot, String relativeFilePath, String methodName) {
        try {
            return analyzeOrThrow(repoRoot, relativeFilePath, methodName);
        } catch (IOException e) {
            log.error("Error reading source file: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Error analyzing call graph: {}", e.getMessage(), e);
            return null;
        }
    }

    private CallGraph analyzeOrThrow(Path repoRoot, String relativeFilePath, String methodName) throws IOException {
        log.info("Analyzing call graph for '{}' in '{}'", methodName, relativeFilePath);

        JavaParser javaParser = projectParserService.getOrCreateParser(repoRoot);
        classMetadataService.ensureInitialized(repoRoot);

        Path absoluteFilePath = repoRoot.resolve(relativeFilePath);
        CompilationUnit paramsCu = parseCompilationUnit(javaParser, absoluteFilePath);
        MethodDeclaration targetMethod = findTargetMethod(paramsCu, methodName, relativeFilePath);
        Map<String, String> dtoClasses = dtoAnalyzer.analyze(targetMethod);
        return callGraphBuilder.build(targetMethod, repoRoot, dtoClasses, maxDepth);
    }

    private List<AnalysisWarning> unresolvedWarnings(FlattenedCallGraph flattened) {
        if (flattened == null || flattened.getMethods() == null) {
            return List.of();
        }
        return flattened.getMethods().stream()
                .filter(method -> method.getCallType() == CallType.UNRESOLVED)
                .map(method -> new AnalysisWarning(
                        "UNRESOLVED_CALL",
                        "Call graph contains an unresolved method",
                        method.getSignature()))
                .toList();
    }

    private AnalysisErrorCode classifyRuntimeFailure(RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : "";
        if (message.contains("not found")) {
            return AnalysisErrorCode.ENTRYPOINT_NOT_FOUND;
        }
        if (message.contains("Failed to parse")) {
            return AnalysisErrorCode.PARSE_FAILED;
        }
        return AnalysisErrorCode.INTERNAL_ERROR;
    }

    private String messageFor(AnalysisErrorCode code) {
        return switch (code) {
            case ENTRYPOINT_NOT_FOUND -> "Entrypoint method was not found";
            case PARSE_FAILED -> "Failed to parse source file";
            default -> "Call graph analysis failed";
        };
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
