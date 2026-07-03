package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.model.AnalysisMetadata;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisStatus;
import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.ResolutionStrategy;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallGraphBuilderResolutionEvidenceTest {

    private JavaCallGraphAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        SourceRootResolver sourceRootResolver = new SourceRootResolver();
        ProjectParserService projectParserService = new ProjectParserService(sourceRootResolver);
        ClassMetadataService classMetadataService = new ClassMetadataService(
                new MapperXmlSqlExtractor(sourceRootResolver),
                new ProjectParserService(sourceRootResolver),
                sourceRootResolver);
        CallGraphBuilder callGraphBuilder = new CallGraphBuilder(
                new CallGraphClassifier(),
                classMetadataService,
                new ScopeTypeResolver());
        analyzer = new JavaCallGraphAnalyzer(
                projectParserService,
                classMetadataService,
                new DtoAnalyzer(),
                callGraphBuilder,
                new CallGraphExplanationMapper(),
                4);
    }

    @Test
    void should_attach_same_class_evidence(@TempDir Path repoRoot) throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    String getBasic(String id) {
                        return normalize(id);
                    }

                    String normalize(String id) {
                        return id.trim();
                    }
                }
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "normalize");
        assertEquals(ResolutionStrategy.SAME_CLASS_METHOD, edge.resolutionStrategy());
        assertTrue(edge.evidence().contains("SAME_CLASS_RECEIVER"));
        assertTrue(edge.evidence().contains("TARGET_METHOD_FOUND"));
    }

    @Test
    void should_attach_static_call_evidence(@TempDir Path repoRoot) throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    String getBasic(String id) {
                        return BasicFormatter.format(id);
                    }
                }
                """);
        writeSource(repoRoot, "BasicFormatter.java", """
                package com.example.basic;

                class BasicFormatter {
                    static String format(String id) {
                        return id.trim();
                    }
                }
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "format");
        assertEquals(ResolutionStrategy.STATIC_METHOD, edge.resolutionStrategy());
        assertTrue(edge.evidence().contains("STATIC_CLASS_SCOPE"));
        assertTrue(edge.evidence().contains("TARGET_METHOD_FOUND"));
    }

    @Test
    void should_attach_spring_field_evidence(@TempDir Path repoRoot) throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    private BasicRepository basicRepository;

                    String getBasic(String id) {
                        return basicRepository.findName(id);
                    }
                }
                """);
        writeSource(repoRoot, "BasicRepository.java", """
                package com.example.basic;

                import org.springframework.stereotype.Repository;

                @Repository
                class BasicRepository {
                    String findName(String id) {
                        return id;
                    }
                }
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "findName");
        assertEquals(ResolutionStrategy.SPRING_BEAN_BY_TYPE, edge.resolutionStrategy());
        assertTrue(edge.evidence().contains("RECEIVER_FIELD_TYPE"));
        assertTrue(edge.evidence().contains("TARGET_METHOD_FOUND"));
    }

    @Test
    void should_attach_unresolved_receiver_evidence(@TempDir Path repoRoot) throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    String getBasic(String id) {
                        return unknownClient.fetch(id);
                    }
                }
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertEquals(AnalysisStatus.PARTIAL, result.status());
        CallEdge edge = edgeTo(result.data(), "fetch");
        assertEquals(ResolutionStrategy.UNRESOLVED, edge.resolutionStrategy());
        assertTrue(edge.evidence().contains("RECEIVER_TYPE_UNRESOLVED"));
    }

    private AnalysisResult<ExplainableCallGraph> analyze(Path repoRoot, String methodName) {
        return analyzer.analyzeExplainableResult(
                "test-repo",
                repoRoot,
                "src/main/java/com/example/basic/BasicService.java",
                methodName,
                AnalysisMetadata.now("test-repo", "com.example.basic", "BasicService", methodName));
    }

    private void writeSource(Path repoRoot, String fileName, String code) throws IOException {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/basic").resolve(fileName);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, code);
    }

    private CallEdge edgeTo(ExplainableCallGraph graph, String methodName) {
        assertNotNull(graph);
        return graph.edges().stream()
                .filter(edge -> methodName.equals(edge.callee().methodName()))
                .findFirst()
                .orElseThrow();
    }
}
