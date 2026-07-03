package com.java.system.agent.analysis.callgraph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.java.system.agent.analysis.fixture.ExpectedGraphLoader;
import com.java.system.agent.analysis.fixture.ExpectedGraphSpec;
import com.java.system.agent.analysis.fixture.FixtureRepoLoader;
import com.java.system.agent.analysis.fixture.GraphAssert;
import com.java.system.agent.analysis.model.AnalysisMetadata;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisStatus;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallGraphFixtureTest {

    private static final String FIXTURE = "spring-basic";
    private static final String CONTROLLER_SOURCE = "com/example/basic/BasicController.java";

    private JavaCallGraphAnalyzer analyzer;
    private FixtureRepoLoader fixtureRepoLoader;
    private ExpectedGraphLoader expectedGraphLoader;

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
        fixtureRepoLoader = new FixtureRepoLoader();
        expectedGraphLoader = new ExpectedGraphLoader(fixtureRepoLoader);
    }

    @Test
    void should_match_controller_service_repository_fixture() {
        FlattenedCallGraph graph = analyzeBasicController();
        ExpectedGraphSpec spec = expectedGraphLoader.load(FIXTURE, "controller-service-repository");

        GraphAssert.assertMatches(graph, spec);
    }

    @Test
    void should_expand_interface_call_to_single_implementation() {
        FlattenedCallGraph graph = analyzeBasicController();
        ExpectedGraphSpec spec = expectedGraphLoader.load(FIXTURE, "interface-single-impl");

        GraphAssert.assertMatches(graph, spec);
    }

    @Test
    void should_extract_mybatis_xml_sql_for_repository_call() {
        FlattenedCallGraph graph = analyzeBasicController();
        ExpectedGraphSpec spec = expectedGraphLoader.load(FIXTURE, "mybatis-xml");

        GraphAssert.assertMatches(graph, spec);
    }

    @Test
    void should_return_explainable_graph_with_legacy_flattened_fixture() {
        AnalysisResult<ExplainableCallGraph> result = analyzer.analyzeExplainableResult(
                FIXTURE,
                fixtureRepoLoader.fixtureRoot(FIXTURE),
                fixtureRepoLoader.sourceFile(FIXTURE, CONTROLLER_SOURCE),
                "getBasic",
                AnalysisMetadata.now(FIXTURE, "com.example.basic", "BasicController", "getBasic"));

        assertNotEquals(AnalysisStatus.FAILED, result.status());
        assertNotNull(result.data());
        assertNotNull(result.data().root());
        assertNotNull(result.data().legacyFlattened());
        assertFalse(result.data().nodes().isEmpty());
        assertFalse(result.data().edges().isEmpty());
        assertFalse(result.data().legacyFlattened().getMethods().isEmpty());
        assertFalse(result.data().edges().stream()
                .filter(edge -> edge.resolutionStrategy() != null)
                .toList()
                .isEmpty());
        assertTrue(result.data().edges().stream()
                .filter(edge -> "findById".equals(edge.callee().methodName()))
                .anyMatch(edge -> edge.evidence().contains("MYBATIS_XML_SQL_FOUND")));
    }

    @Test
    void should_return_partial_when_explainable_graph_contains_unresolved_edge(@TempDir Path repoRoot)
            throws IOException {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/ExampleController.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example;

                class ExampleController {
                    void run() {
                    }
                }
                """);
        ProjectParserService projectParserService = mock(ProjectParserService.class);
        ClassMetadataService classMetadataService = mock(ClassMetadataService.class);
        DtoAnalyzer dtoAnalyzer = mock(DtoAnalyzer.class);
        CallGraphBuilder callGraphBuilder = mock(CallGraphBuilder.class);
        JavaCallGraphAnalyzer analyzerWithUnresolvedEdge = new JavaCallGraphAnalyzer(
                projectParserService,
                classMetadataService,
                dtoAnalyzer,
                callGraphBuilder,
                new CallGraphExplanationMapper(),
                4);
        CallGraph unresolvedChild = CallGraph.leaf(
                null,
                "MissingService",
                "missing",
                CallType.UNRESOLVED,
                null);
        CallGraph root = CallGraph.builder()
                .signature("com.example.ExampleController#void run()")
                .className("ExampleController")
                .packagePath("com.example")
                .methodName("run")
                .callType(CallType.INTERNAL_CONTROLLER)
                .calledMethods(List.of(unresolvedChild))
                .build();

        when(projectParserService.getOrCreateParser(repoRoot)).thenReturn(new JavaParser());
        when(dtoAnalyzer.analyze(any(MethodDeclaration.class))).thenReturn(Map.of());
        when(callGraphBuilder.build(any(MethodDeclaration.class), eq(repoRoot), anyMap(), eq(4)))
                .thenReturn(root);

        AnalysisResult<ExplainableCallGraph> result = analyzerWithUnresolvedEdge.analyzeExplainableResult(
                "test-repo",
                repoRoot,
                repoRoot.relativize(sourceFile).toString(),
                "run",
                AnalysisMetadata.now("test-repo", "com.example", "ExampleController", "run"));

        assertEquals(AnalysisStatus.PARTIAL, result.status());
        assertTrue(result.warnings().stream()
                .anyMatch(warning -> "UNRESOLVED_CALL".equals(warning.code())));
        assertTrue(result.warnings().stream()
                .anyMatch(warning -> "MissingService#missing".equals(warning.location())));
        assertTrue(result.data().edges().stream()
                .anyMatch(edge -> ResolutionStrategy.UNRESOLVED.equals(edge.resolutionStrategy())));
    }

    private FlattenedCallGraph analyzeBasicController() {
        CallGraph graph = analyzer.analyze(
                fixtureRepoLoader.fixtureRoot(FIXTURE),
                fixtureRepoLoader.sourceFile(FIXTURE, CONTROLLER_SOURCE),
                "getBasic");
        return CallGraphVisitor.flattenToOptimized(graph, fixtureGraphConfig());
    }

    private GraphVisitorConfig fixtureGraphConfig() {
        return GraphVisitorConfig.builder()
                .keepType(CallType.INTERNAL_CONTROLLER)
                .keepType(CallType.INTERNAL_SERVICE)
                .keepType(CallType.INTERNAL_COMPONENT)
                .keepType(CallType.INTERNAL_CLASS)
                .keepType(CallType.INTERNAL_SCHEDULE)
                .keepType(CallType.INTERNAL_INTERFACE_DEFAULT)
                .keepType(CallType.TRAVERSAL_CUTOFF)
                .keepType(CallType.RPC_CLIENT)
                .keepType(CallType.DATA_ACCESS)
                .keepType(CallType.MESSAGE_QUEUE)
                .keepType(CallType.INTERFACE)
                .codeInclusionType(CallType.INTERNAL_CONTROLLER)
                .codeInclusionType(CallType.INTERNAL_SERVICE)
                .codeInclusionType(CallType.INTERNAL_COMPONENT)
                .codeInclusionType(CallType.INTERNAL_CLASS)
                .codeInclusionType(CallType.DATA_ACCESS)
                .codeInclusionType(CallType.INTERNAL_SCHEDULE)
                .codeInclusionType(CallType.INTERNAL_INTERFACE_DEFAULT)
                .codeInclusionType(CallType.TRAVERSAL_CUTOFF)
                .codeInclusionType(CallType.MESSAGE_QUEUE)
                .build();
    }
}
