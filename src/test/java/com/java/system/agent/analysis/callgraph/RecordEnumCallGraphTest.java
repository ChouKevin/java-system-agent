package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.fixture.ExpectedGraphSpec;
import com.java.system.agent.analysis.fixture.FixtureRepoLoader;
import com.java.system.agent.analysis.fixture.GraphAssert;
import com.java.system.agent.analysis.model.ClassMetadata;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordEnumCallGraphTest {

    private static final String FIXTURE = "record-enum";

    private JavaCallGraphAnalyzer analyzer;
    private ClassMetadataService classMetadataService;
    private Path fixtureRoot;

    @BeforeEach
    void setUp() {
        SourceRootResolver sourceRootResolver = new SourceRootResolver();
        ProjectParserService projectParserService = new ProjectParserService(sourceRootResolver);
        classMetadataService = new ClassMetadataService(
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
        fixtureRoot = new FixtureRepoLoader().fixtureRoot(FIXTURE);
    }

    @Test
    void should_index_record_and_enum_metadata_when_snapshot_is_built() {
        classMetadataService.ensureInitialized(fixtureRoot);

        Optional<ClassMetadata> recordMetadata = classMetadataService.findClassMetadata(
                fixtureRoot, "OrderPricing", "com.example.recordenum");
        Optional<ClassMetadata> enumMetadata = classMetadataService.findClassMetadata(
                fixtureRoot, "OrderStatus", "com.example.recordenum");

        assertTrue(recordMetadata.isPresent(), "record metadata should be indexed");
        assertTrue(recordMetadata.get().methods().stream()
                .anyMatch(method -> method.name().equals("applyRule")));
        assertTrue(recordMetadata.get().fields().stream()
                        .anyMatch(field -> field.name().equals("amount")),
                "record component should be exposed as field metadata");
        assertTrue(enumMetadata.isPresent(), "enum metadata should be indexed");
        assertTrue(enumMetadata.get().methods().stream()
                .anyMatch(method -> method.name().equals("isFinal")));
    }

    @Test
    void should_classify_calls_to_record_and_enum_methods_as_internal() {
        FlattenedCallGraph graph = analyzer.analyzeFlattened(
                fixtureRoot,
                "src/main/java/com/example/recordenum/PricingController.java",
                "quote");

        ExpectedGraphSpec spec = new ExpectedGraphSpec(
                "com.example.recordenum.PricingController#quote(long)",
                3,
                2,
                List.of(
                        new ExpectedGraphSpec.NodeSpec(
                                "PricingController#", "INTERNAL_CONTROLLER", "PricingController"),
                        new ExpectedGraphSpec.NodeSpec(
                                "OrderPricing#total", "INTERNAL_CLASS", "OrderPricing"),
                        new ExpectedGraphSpec.NodeSpec(
                                "OrderStatus#isFinal", "INTERNAL_CLASS", "OrderStatus")),
                List.of(
                        new ExpectedGraphSpec.EdgeSpec("PricingController#", "OrderPricing#total"),
                        new ExpectedGraphSpec.EdgeSpec("PricingController#", "OrderStatus#isFinal")),
                List.of());

        GraphAssert.assertMatches(graph, spec);
    }

    @Test
    void should_resolve_calls_inside_record_method_when_record_is_analysis_root() {
        FlattenedCallGraph graph = analyzer.analyzeFlattened(
                fixtureRoot,
                "src/main/java/com/example/recordenum/OrderPricing.java",
                "applyRule");

        ExpectedGraphSpec spec = new ExpectedGraphSpec(
                "com.example.recordenum.OrderPricing#applyRule(PricingRule)",
                3,
                2,
                List.of(
                        new ExpectedGraphSpec.NodeSpec(
                                "OrderPricing#", "INTERNAL_CLASS", "OrderPricing"),
                        new ExpectedGraphSpec.NodeSpec(
                                "PricingRule#", "INTERNAL_COMPONENT", "PricingRule")),
                List.of(new ExpectedGraphSpec.EdgeSpec("OrderPricing#", "PricingRule#")),
                List.of());

        GraphAssert.assertMatches(graph, spec);
    }
}
