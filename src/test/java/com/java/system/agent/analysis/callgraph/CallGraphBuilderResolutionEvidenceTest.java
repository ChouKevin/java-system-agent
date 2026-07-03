package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.model.AnalysisMetadata;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisStatus;
import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.CallNode;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void should_not_claim_xml_evidence_for_annotation_sql(@TempDir Path repoRoot) throws IOException {
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

                import org.apache.ibatis.annotations.Mapper;
                import org.apache.ibatis.annotations.Select;

                @Mapper
                interface BasicRepository {
                    @Select("SELECT name FROM basic WHERE id = #{id}")
                    String findName(String id);
                }
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "findName");
        assertEquals(ResolutionStrategy.MYBATIS_MAPPER, edge.resolutionStrategy());
        assertFalse(edge.evidence().contains("MYBATIS_XML_SQL_FOUND"));
        assertTrue(edge.evidence().contains("MYBATIS_ANNOTATION_SQL_FOUND"));
    }

    @Test
    void should_align_sql_evidence_with_displayed_xml_sql_when_annotation_and_xml_exist(@TempDir Path repoRoot)
            throws IOException {
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

                import org.apache.ibatis.annotations.Mapper;
                import org.apache.ibatis.annotations.Select;

                @Mapper
                interface BasicRepository {
                    @Select("SELECT annotation_name FROM basic WHERE id = #{id}")
                    String findName(String id);
                }
                """);
        writeResource(repoRoot, "mapper/BasicRepository.xml", """
                <?xml version="1.0" encoding="UTF-8" ?>
                <mapper namespace="com.example.basic.BasicRepository">
                    <select id="findName">
                        SELECT xml_name FROM basic WHERE id = #{id}
                    </select>
                </mapper>
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "findName");
        CallNode node = nodeFor(result.data(), "findName");
        assertTrue(node.code().contains("xml_name"));
        assertFalse(node.code().contains("annotation_name"));
        assertTrue(edge.evidence().contains("MYBATIS_XML_SQL_FOUND"));
        assertFalse(edge.evidence().contains("MYBATIS_ANNOTATION_SQL_FOUND"));
    }

    @Test
    void should_not_claim_mybatis_mapper_annotation_for_non_mybatis_data_access(@TempDir Path repoRoot)
            throws IOException {
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

                import org.springframework.data.jpa.repository.JpaRepository;

                interface BasicRepository extends JpaRepository<BasicEntity, String> {
                    String findName(String id);
                }

                class BasicEntity {
                }
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "findName");
        assertEquals(ResolutionStrategy.MYBATIS_MAPPER, edge.resolutionStrategy());
        assertFalse(edge.evidence().contains("MYBATIS_MAPPER_ANNOTATION"));
        assertTrue(edge.evidence().contains("DATA_ACCESS_METADATA_FOUND"));
    }

    @Test
    void should_not_label_interface_without_implementation_as_single_impl(@TempDir Path repoRoot)
            throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    private BasicGateway basicGateway;

                    String getBasic(String id) {
                        return basicGateway.fetch(id);
                    }
                }
                """);
        writeSource(repoRoot, "BasicGateway.java", """
                package com.example.basic;

                interface BasicGateway {
                    String fetch(String id);
                }
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "fetch");
        assertEquals(ResolutionStrategy.UNRESOLVED, edge.resolutionStrategy());
        assertFalse(edge.evidence().contains("SINGLE_INTERFACE_IMPLEMENTATION"));
        assertTrue(edge.evidence().contains("METHOD_SOURCE_NOT_FOUND"));
        assertTrue(edge.warnings().contains("No interface implementation matched"));
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

    private void writeResource(Path repoRoot, String fileName, String content) throws IOException {
        Path resourceFile = repoRoot.resolve("src/main/resources").resolve(fileName);
        Files.createDirectories(resourceFile.getParent());
        Files.writeString(resourceFile, content);
    }

    private CallEdge edgeTo(ExplainableCallGraph graph, String methodName) {
        assertNotNull(graph);
        return graph.edges().stream()
                .filter(edge -> methodName.equals(edge.callee().methodName()))
                .findFirst()
                .orElseThrow();
    }

    private CallNode nodeFor(ExplainableCallGraph graph, String methodName) {
        assertNotNull(graph);
        return graph.nodes().stream()
                .filter(node -> methodName.equals(node.methodId().methodName()))
                .findFirst()
                .orElseThrow();
    }
}
