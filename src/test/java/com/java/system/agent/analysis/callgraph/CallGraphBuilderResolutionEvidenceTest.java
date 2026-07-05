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
    void should_resolve_data_access_nodes_across_multi_module_fixture() {
        Path repoRoot = Path.of("src/test/resources/fixtures/multi-module-data-access");

        AnalysisResult<ExplainableCallGraph> result = analyzer.analyzeExplainableResult(
                "test-repo",
                repoRoot,
                "module-service/src/main/java/com/example/service/OrderApplicationService.java",
                "loadOrder",
                AnalysisMetadata.now(
                        "test-repo",
                        "com.example.service",
                        "OrderApplicationService",
                        "loadOrder"));

        assertEquals(AnalysisStatus.SUCCESS, result.status());
        assertEquals(CallType.DATA_ACCESS, nodeForClass(result.data(), "OrderMapper").callType());
        assertEquals(CallType.DATA_ACCESS, nodeForClass(result.data(), "OrderJpaRepository").callType());
        assertEquals(CallType.DATA_ACCESS, nodeForClass(result.data(), "JdbcOrderRepository").callType());
        assertTrue(result.data().edges().stream()
                .anyMatch(edge -> "OrderMapper".equals(edge.callee().className())));
        assertTrue(result.data().edges().stream()
                .anyMatch(edge -> "OrderJpaRepository".equals(edge.callee().className())));
        assertTrue(result.data().edges().stream()
                .anyMatch(edge -> "JdbcOrderRepository".equals(edge.callee().className())));
    }

    @Test
    void should_attach_repo_relative_source_locations_to_nodes_and_edges(@TempDir Path repoRoot)
            throws IOException {
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
        CallNode rootNode = nodeFor(result.data(), "getBasic");
        CallNode calleeNode = nodeFor(result.data(), "normalize");
        CallEdge edge = edgeTo(result.data(), "normalize");
        assertEquals("src/main/java/com/example/basic/BasicService.java", rootNode.sourceFile());
        assertEquals(7, rootNode.startLine());
        assertEquals(9, rootNode.endLine());
        assertEquals("src/main/java/com/example/basic/BasicService.java", calleeNode.sourceFile());
        assertEquals(11, calleeNode.startLine());
        assertEquals(13, calleeNode.endLine());
        assertEquals("src/main/java/com/example/basic/BasicService.java", edge.sourceFile());
        assertEquals(8, edge.lineNumber());
    }

    @Test
    void should_attach_callsite_source_file_to_max_depth_edges(@TempDir Path repoRoot)
            throws IOException {
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

        JavaCallGraphAnalyzer cutoffAnalyzer = analyzerWithMaxDepth(0);
        AnalysisResult<ExplainableCallGraph> result = cutoffAnalyzer.analyzeExplainableResult(
                "test-repo",
                repoRoot,
                "src/main/java/com/example/basic/BasicService.java",
                "getBasic",
                AnalysisMetadata.now("test-repo", "com.example.basic", "BasicService", "getBasic"));

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "normalize");
        CallNode node = nodeFor(result.data(), "normalize");
        assertEquals("src/main/java/com/example/basic/BasicService.java", node.sourceFile());
        assertEquals(11, node.startLine());
        assertEquals(13, node.endLine());
        assertEquals("src/main/java/com/example/basic/BasicService.java", edge.sourceFile());
        assertEquals(8, edge.lineNumber());
        assertEquals(ResolutionStrategy.SAME_CLASS_METHOD, edge.resolutionStrategy());
        assertEquals(0.95, edge.confidence());
        assertTrue(edge.evidence().contains("SAME_CLASS_RECEIVER"));
        assertTrue(edge.evidence().contains("TRAVERSAL_CUTOFF_CALLEE"));
        assertTrue(edge.warnings().contains("Traversal cutoff prevented deeper analysis"));
    }

    @Test
    void should_keep_unresolved_callsite_edge_when_max_depth_stops_traversal(@TempDir Path repoRoot)
            throws IOException {
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

        JavaCallGraphAnalyzer cutoffAnalyzer = analyzerWithMaxDepth(0);
        AnalysisResult<ExplainableCallGraph> result = cutoffAnalyzer.analyzeExplainableResult(
                "test-repo",
                repoRoot,
                "src/main/java/com/example/basic/BasicService.java",
                "getBasic",
                AnalysisMetadata.now("test-repo", "com.example.basic", "BasicService", "getBasic"));

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "fetch");
        assertEquals(ResolutionStrategy.UNRESOLVED, edge.resolutionStrategy());
        assertEquals("src/main/java/com/example/basic/BasicService.java", edge.sourceFile());
        assertEquals(8, edge.lineNumber());
    }

    @Test
    void should_keep_missing_metadata_callsite_edge_when_max_depth_stops_traversal(@TempDir Path repoRoot)
            throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    private ExternalClient externalClient;

                    String getBasic(String id) {
                        return externalClient.fetch(id);
                    }
                }
                """);

        JavaCallGraphAnalyzer cutoffAnalyzer = analyzerWithMaxDepth(0);
        AnalysisResult<ExplainableCallGraph> result = cutoffAnalyzer.analyzeExplainableResult(
                "test-repo",
                repoRoot,
                "src/main/java/com/example/basic/BasicService.java",
                "getBasic",
                AnalysisMetadata.now("test-repo", "com.example.basic", "BasicService", "getBasic"));

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "fetch");
        assertEquals(ResolutionStrategy.UNKNOWN, edge.resolutionStrategy());
        assertEquals("src/main/java/com/example/basic/BasicService.java", edge.sourceFile());
        assertEquals(10, edge.lineNumber());
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
    void should_attach_constructor_injection_evidence(@TempDir Path repoRoot) throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    private final BasicRepository basicRepository;

                    BasicService(BasicRepository basicRepository) {
                        this.basicRepository = basicRepository;
                    }

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
        assertTrue(edge.evidence().contains("RECEIVER_CONSTRUCTOR_PARAMETER_TYPE"));
        assertTrue(edge.evidence().contains("TARGET_METHOD_FOUND"));
    }

    @Test
    void should_attach_constructor_qualifier_evidence(@TempDir Path repoRoot) throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    private final BasicRepository basicRepository;

                    BasicService(@Qualifier("primaryBasicRepository") BasicRepository basicRepository) {
                        this.basicRepository = basicRepository;
                    }

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
        assertEquals(ResolutionStrategy.SPRING_BEAN_BY_QUALIFIER, edge.resolutionStrategy());
        assertTrue(edge.evidence().contains("RECEIVER_CONSTRUCTOR_PARAMETER_TYPE"));
        assertTrue(edge.evidence().contains("SPRING_QUALIFIER:primaryBasicRepository"));
        assertTrue(edge.evidence().contains("TARGET_METHOD_FOUND"));
    }

    @Test
    void should_keep_field_evidence_when_constructor_assignment_has_no_matching_parameter(
            @TempDir Path repoRoot) throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    private final BasicRepository basicRepository;

                    BasicService() {
                        this.basicRepository = createRepository();
                    }

                    String getBasic(String id) {
                        return basicRepository.findName(id);
                    }
                }
                """);
        writeSource(repoRoot, "BasicRepository.java", """
                package com.example.basic;

                class BasicRepository {
                    String findName(String id) {
                        return id;
                    }
                }
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallEdge edge = edgeTo(result.data(), "findName");
        assertEquals(ResolutionStrategy.HEURISTIC_NAME_MATCH, edge.resolutionStrategy());
        assertFalse(edge.evidence().contains("RECEIVER_CONSTRUCTOR_PARAMETER_TYPE"));
    }

    @Test
    void should_treat_parameter_and_local_variable_receiver_types_as_inferred_not_spring_beans(
            @TempDir Path repoRoot) throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    String fromParameter(BasicCollaborator collaborator, String id) {
                        return collaborator.fromParameter(id);
                    }

                    String fromLocalVariable(String id) {
                        BasicCollaborator collaborator = new BasicCollaborator();
                        return collaborator.fromLocalVariable(id);
                    }
                }
                """);
        writeSource(repoRoot, "BasicCollaborator.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicCollaborator {
                    String fromParameter(String id) {
                        return id;
                    }

                    String fromLocalVariable(String id) {
                        return id;
                    }
                }
                """);

        AnalysisResult<ExplainableCallGraph> parameterResult = analyze(repoRoot, "fromParameter");
        AnalysisResult<ExplainableCallGraph> localVariableResult = analyze(repoRoot, "fromLocalVariable");

        assertNotEquals(AnalysisStatus.FAILED, parameterResult.status());
        CallEdge parameterEdge = edgeTo(parameterResult.data(), "fromParameter");
        assertEquals(ResolutionStrategy.HEURISTIC_NAME_MATCH, parameterEdge.resolutionStrategy());
        assertEquals(0.65, parameterEdge.confidence());
        assertTrue(parameterEdge.evidence().contains("RECEIVER_PARAMETER_TYPE"));
        assertTrue(parameterEdge.evidence().contains("TARGET_METHOD_FOUND"));
        assertTrue(parameterEdge.warnings().contains("Receiver type inferred from parameter or local variable"));

        assertNotEquals(AnalysisStatus.FAILED, localVariableResult.status());
        CallEdge localVariableEdge = edgeTo(localVariableResult.data(), "fromLocalVariable");
        assertEquals(ResolutionStrategy.HEURISTIC_NAME_MATCH, localVariableEdge.resolutionStrategy());
        assertEquals(0.65, localVariableEdge.confidence());
        assertTrue(localVariableEdge.evidence().contains("RECEIVER_LOCAL_VARIABLE_TYPE"));
        assertTrue(localVariableEdge.evidence().contains("TARGET_METHOD_FOUND"));
        assertTrue(localVariableEdge.warnings().contains("Receiver type inferred from parameter or local variable"));
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
    void should_attach_repo_relative_source_file_to_unresolved_edge(@TempDir Path repoRoot)
            throws IOException {
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
        assertEquals("src/main/java/com/example/basic/BasicService.java", edge.sourceFile());
        assertEquals(8, edge.lineNumber());
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
        CallNode node = nodeFor(result.data(), "findName");
        assertEquals("src/main/java/com/example/basic/BasicRepository.java", node.sourceFile());
        assertEquals(8, node.startLine());
        assertEquals(9, node.endLine());
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
        assertEquals(ResolutionStrategy.UNKNOWN, edge.resolutionStrategy());
        assertEquals(0.70, edge.confidence());
        assertFalse(edge.evidence().contains("MYBATIS_MAPPER_ANNOTATION"));
        assertFalse(edge.evidence().contains("MYBATIS_XML_SQL_FOUND"));
        assertFalse(edge.evidence().contains("MYBATIS_ANNOTATION_SQL_FOUND"));
        assertTrue(edge.evidence().contains("DATA_ACCESS_METADATA_FOUND"));
        assertTrue(edge.warnings().contains("Data access metadata detected without MyBatis mapper evidence"));
    }

    @Test
    void should_attach_metadata_source_location_to_filtered_leaf(@TempDir Path repoRoot)
            throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    private PlainHelper plainHelper;

                    String getBasic(String id) {
                        return plainHelper.clean(id);
                    }
                }
                """);
        writeSource(repoRoot, "PlainHelper.java", """
                package com.example.basic;

                class PlainHelper {
                    String clean(String id) {
                        return id.trim();
                    }
                }
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallNode node = nodeFor(result.data(), "clean");
        assertEquals("src/main/java/com/example/basic/PlainHelper.java", node.sourceFile());
        assertEquals(4, node.startLine());
        assertEquals(6, node.endLine());
    }

    @Test
    void should_not_attach_exact_span_when_metadata_method_signature_is_ambiguous(@TempDir Path repoRoot)
            throws IOException {
        writeSource(repoRoot, "BasicService.java", """
                package com.example.basic;

                import org.springframework.stereotype.Service;

                @Service
                class BasicService {
                    private PlainHelper plainHelper;

                    String getBasic(String id) {
                        return plainHelper.clean(id);
                    }
                }
                """);
        writeSource(repoRoot, "PlainHelper.java", """
                package com.example.basic;

                class PlainHelper {
                    String clean(Integer id) {
                        return id.toString();
                    }

                    String clean(String id) {
                        return id.trim();
                    }
                }
                """);

        AnalysisResult<ExplainableCallGraph> result = analyze(repoRoot, "getBasic");

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        CallNode node = nodeFor(result.data(), "clean");
        assertEquals("src/main/java/com/example/basic/PlainHelper.java", node.sourceFile());
        assertEquals(null, node.startLine());
        assertEquals(null, node.endLine());
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
        CallNode node = nodeFor(result.data(), "fetch");
        assertEquals("src/main/java/com/example/basic/BasicGateway.java", node.sourceFile());
        assertEquals(4, node.startLine());
        assertEquals(4, node.endLine());
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

    private JavaCallGraphAnalyzer analyzerWithMaxDepth(int maxDepth) {
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
        return new JavaCallGraphAnalyzer(
                projectParserService,
                classMetadataService,
                new DtoAnalyzer(),
                callGraphBuilder,
                new CallGraphExplanationMapper(),
                maxDepth);
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

    private CallNode nodeForClass(ExplainableCallGraph graph, String className) {
        assertNotNull(graph);
        return graph.nodes().stream()
                .filter(node -> className.equals(node.methodId().className()))
                .findFirst()
                .orElseThrow();
    }
}
