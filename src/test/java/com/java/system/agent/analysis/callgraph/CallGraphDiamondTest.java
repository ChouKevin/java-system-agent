package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.fixture.FixtureRepoLoader;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.FlattenedMethodNode;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallGraphDiamondTest {

    private static final String FIXTURE = "legacy-callgraph";

    private JavaCallGraphAnalyzer analyzer;
    private Path fixtureRoot;

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
                6);
        fixtureRoot = new FixtureRepoLoader().fixtureRoot(FIXTURE);
    }

    @Test
    void should_keep_shared_callee_edge_from_both_diamond_branches() {
        FlattenedCallGraph graph = analyzer.analyzeFlattened(
                fixtureRoot, "src/main/java/com/example/diamond/DiamondController.java", "entry");

        FlattenedMethodNode left = nodeOf(graph, "DiamondServiceA", "left");
        FlattenedMethodNode right = nodeOf(graph, "DiamondServiceB", "right");
        FlattenedMethodNode shared = nodeOf(graph, "SharedLeafService", "work");

        assertTrue(left.getCallees().contains(shared.getSignature()),
                "left should keep the shared callee edge");
        assertTrue(right.getCallees().contains(shared.getSignature()),
                "right should keep the shared callee edge from the independent diamond branch");
    }

    @Test
    void should_keep_visible_cycle_back_edge_when_true_cycle_is_detected() {
        CallGraph rawGraph = analyzer.analyze(
                fixtureRoot, "src/main/java/com/example/diamond/CycleServiceX.java", "ping");
        FlattenedCallGraph graph = CallGraphVisitor.flattenToOptimized(rawGraph, GraphVisitorConfig.defaultConfig());

        CallGraph cycleBackEdge = rawGraph.getCalledMethods().get(0).getCalledMethods().get(0);
        assertEquals(CallType.CYCLE_BACK_EDGE, cycleBackEdge.getCallType());

        FlattenedMethodNode ping = nodeOf(graph, "CycleServiceX", "ping");
        FlattenedMethodNode pong = nodeOf(graph, "CycleServiceY", "pong");

        assertTrue(ping.getCallees().contains(pong.getSignature()));
        assertTrue(pong.getCallees().contains(ping.getSignature()),
                "true cycle should keep a visible back edge to the ancestor method");
    }

    private FlattenedMethodNode nodeOf(FlattenedCallGraph graph, String className, String methodName) {
        FlattenedMethodNode node = graph.getMethods().stream()
                .filter(method -> className.equals(method.getClassName()) && methodName.equals(method.getMethodName()))
                .findFirst()
                .orElse(null);
        assertNotNull(node, className + "#" + methodName + " should appear in flattened call graph");
        return node;
    }
}
