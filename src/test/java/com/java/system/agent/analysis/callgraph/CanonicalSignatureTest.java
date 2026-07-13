package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.fixture.FixtureRepoLoader;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.FlattenedMethodNode;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class CanonicalSignatureTest {

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
                new CallGraphClassifier(), classMetadataService, new ScopeTypeResolver());
        analyzer = new JavaCallGraphAnalyzer(
                projectParserService, classMetadataService, new DtoAnalyzer(), callGraphBuilder,
                new CallGraphExplanationMapper(), 3);
        fixtureRoot = new FixtureRepoLoader().fixtureRoot(FIXTURE);
    }

    @Test
    void should_join_cutoff_callee_signature_with_expanded_entry() {
        FlattenedCallGraph graph = analyzeEntry();
        FlattenedMethodNode cutoff = graph.getMethods().stream()
                .filter(node -> CallType.TRAVERSAL_CUTOFF.equals(node.getCallType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TRAVERSAL_CUTOFF node not found"));
        FlattenedMethodNode assist = graph.getMethods().stream()
                .filter(node -> "CutoffHelperService".equals(node.getClassName())
                        && "assist".equals(node.getMethodName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expanded assist entry not found"));

        Assertions.assertEquals("CutoffFinishService", cutoff.getClassName());
        Assertions.assertTrue(cutoff.getCallees().contains(assist.getSignature()),
                "cutoff callee signature should join the expanded entry; callees="
                        + cutoff.getCallees() + ", entry=" + assist.getSignature());
    }

    @Test
    void should_render_canonical_root_signature() {
        FlattenedCallGraph graph = analyzeEntry();

        Assertions.assertEquals(
                "com.example.cutoffdup.CutoffDupController#entry()",
                graph.getRootSignature());
    }

    private FlattenedCallGraph analyzeEntry() {
        return analyzer.analyzeFlattened(
                fixtureRoot,
                "src/main/java/com/example/cutoffdup/CutoffDupController.java",
                "entry");
    }
}
