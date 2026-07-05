package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import com.java.system.agent.analysis.model.ClassMetadata;
import com.java.system.agent.analysis.model.MethodRef;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Tests for:
 * 1. SQL extraction from MyBatis annotations (@Select, etc.)
 * 2. Multiple interface implementations with @Profile annotation
 * 3. Strategy pattern resolution
 */
public class SqlAndProfileTest {

    private static final Path FIXTURE_REPO =
            Paths.get("src/test/resources/fixtures/multi-module-data-access");

    private TestRepoService service;
    private ClassMetadataService classMetadataService;

    @BeforeEach
    void setUp() {
        ProjectParserService projectParserService = new ProjectParserService(new SourceRootResolver());
        CallGraphClassifier classifier = new CallGraphClassifier();
        EntryPointCacheService entryPointCacheService = new EntryPointCacheService(projectParserService, new SourceRootResolver());
        DtoAnalyzer dtoAnalyzer = new DtoAnalyzer();

        classMetadataService = new ClassMetadataService(new MapperXmlSqlExtractor(new SourceRootResolver()), new ProjectParserService(new SourceRootResolver()), new SourceRootResolver());
        ScopeTypeResolver scopeTypeResolver = new ScopeTypeResolver();
        CallGraphBuilder callGraphBuilder = new CallGraphBuilder(classifier, classMetadataService, scopeTypeResolver);

        JavaCallGraphAnalyzer javaCallGraphAnalyzer = new JavaCallGraphAnalyzer(projectParserService, classMetadataService, dtoAnalyzer, callGraphBuilder, 5);
        service = new TestRepoService(FIXTURE_REPO, entryPointCacheService, javaCallGraphAnalyzer);
        classMetadataService.ensureInitialized(FIXTURE_REPO);
    }

    // =========================================================================
    // SQL Extraction Tests
    // =========================================================================

    @Test
    public void testAnnotationSqlExtraction() {
        // UserMapper.findById has @Select("SELECT * FROM users WHERE id = #{userId}")
        CallGraph graph = service.readJavaSource(new MethodRef("com.example.strategy", "PaymentService", "checkout"));

        Assertions.assertNotNull(graph);

        // Find the DATA_ACCESS node for UserMapper.findById
        CallGraph mapperCall = findChildByMethodName(graph, "findById");
        Assertions.assertNotNull(mapperCall, "findById call should be found");
        Assertions.assertEquals(CallType.DATA_ACCESS, mapperCall.getCallType(), "Should be DATA_ACCESS");
        Assertions.assertNotNull(mapperCall.getCode(), "SQL should be extracted from @Select annotation");
        Assertions.assertTrue(mapperCall.getCode().contains("SELECT"), "SQL should contain SELECT statement");

    }

    @Test
    public void testAnnotationSqlInMetadata() {
        // Directly verify that UserMapper metadata has SQL in MethodSignature
        Path repoRoot = FIXTURE_REPO;
        Optional<ClassMetadata> metadataOpt = classMetadataService.findClassMetadata(repoRoot, "UserMapper", "com.example.repository");

        Assertions.assertTrue(metadataOpt.isPresent(), "UserMapper metadata should exist");
        ClassMetadata mapperMeta = metadataOpt.get();

        // Check findById method has SQL
        Optional<ClassMetadata.MethodSignature> findByIdMethod = mapperMeta.methods().stream()
                .filter(m -> m.name().equals("findById"))
                .findFirst();

        Assertions.assertTrue(findByIdMethod.isPresent(), "findById method should exist");
        Assertions.assertNotNull(findByIdMethod.get().sql(), "findById should have SQL extracted from @Select");
        Assertions.assertTrue(findByIdMethod.get().sql().contains("SELECT * FROM users"), "SQL should match annotation value");

        // Check findByUsername
        Optional<ClassMetadata.MethodSignature> findByUsername = mapperMeta.methods().stream()
                .filter(m -> m.name().equals("findByUsername"))
                .findFirst();

        Assertions.assertTrue(findByUsername.isPresent(), "findByUsername should exist");
        Assertions.assertNotNull(findByUsername.get().sql(), "findByUsername should have SQL");
        Assertions.assertTrue(findByUsername.get().sql().contains("username"), "SQL should reference username");
    }

    // =========================================================================
    // Multiple Implementation & Profile Tests
    // =========================================================================

    @Test
    public void testMultipleImplementationsFound() {
        // PaymentService.checkout() calls paymentStrategy.processPayment()
        // PaymentStrategy has 2 implementations: StripePaymentStrategy and PayPalPaymentStrategy
        CallGraph graph = service.readJavaSource(new MethodRef("com.example.strategy", "PaymentService", "checkout"));

        Assertions.assertNotNull(graph);

        // Find the INTERFACE node for PaymentStrategy.processPayment
        CallGraph strategyCall = findChildByMethodName(graph, "processPayment");
        Assertions.assertNotNull(strategyCall, "processPayment call should be found");
        Assertions.assertEquals(CallType.INTERFACE, strategyCall.getCallType(), "Should be INTERFACE type");

        // Verify all implementations are found (including pure Java class without Spring annotation)
        List<CallGraph> impls = strategyCall.getCalledMethods();
        Assertions.assertNotNull(impls, "Implementations should not be null");
        Assertions.assertEquals(3, impls.size(),
                "Should find 3 implementations (Stripe + PayPal + Cash)");

        boolean stripeFound = impls.stream().anyMatch(c -> "StripePaymentStrategy".equals(c.getClassName()));
        boolean paypalFound = impls.stream().anyMatch(c -> "PayPalPaymentStrategy".equals(c.getClassName()));
        boolean cashFound = impls.stream().anyMatch(c -> "CashPaymentStrategy".equals(c.getClassName()));

        Assertions.assertTrue(stripeFound, "StripePaymentStrategy implementation should be found");
        Assertions.assertTrue(paypalFound, "PayPalPaymentStrategy implementation should be found");
        Assertions.assertTrue(cashFound,
                "CashPaymentStrategy (pure Java, no Spring annotation) should be found");

    }

    @Test
    public void testProfileAnnotationInMetadata() {
        // Verify that @Profile annotations are correctly extracted into metadata
        Path repoRoot = FIXTURE_REPO;

        // Check StripePaymentStrategy
        Optional<ClassMetadata> stripeMeta = classMetadataService.findClassMetadata(repoRoot, "StripePaymentStrategy", "com.example.strategy");
        Assertions.assertTrue(stripeMeta.isPresent(), "StripePaymentStrategy metadata should exist");
        Assertions.assertFalse(stripeMeta.get().profiles().isEmpty(), "Should have @Profile annotation");
        Assertions.assertTrue(stripeMeta.get().profiles().contains("stripe"), "Should have profile 'stripe'");

        // Check PayPalPaymentStrategy
        Optional<ClassMetadata> paypalMeta = classMetadataService.findClassMetadata(repoRoot, "PayPalPaymentStrategy", "com.example.strategy");
        Assertions.assertTrue(paypalMeta.isPresent(), "PayPalPaymentStrategy metadata should exist");
        Assertions.assertFalse(paypalMeta.get().profiles().isEmpty(), "Should have @Profile annotation");
        Assertions.assertTrue(paypalMeta.get().profiles().contains("paypal"), "Should have profile 'paypal'");
    }

    @Test
    public void testProfileInfoInCallGraph() {
        // Verify that the CallGraph nodes include Profile info in their doc
        CallGraph graph = service.readJavaSource(new MethodRef("com.example.strategy", "PaymentService", "checkout"));

        CallGraph strategyCall = findChildByMethodName(graph, "processPayment");
        Assertions.assertNotNull(strategyCall, "processPayment should be found");

        List<CallGraph> impls = strategyCall.getCalledMethods();
        Assertions.assertNotNull(impls);

        // @Profile 實作應帶有 Profile info，純 Java class 不應有
        CallGraph stripeImpl = impls.stream()
                .filter(c -> "StripePaymentStrategy".equals(c.getClassName()))
                .findFirst().orElseThrow();
        Assertions.assertTrue(stripeImpl.getDesc().contains("[Profiles:"),
                "Stripe impl should show profile label");
        Assertions.assertTrue(stripeImpl.getDesc().contains("stripe"),
                "Stripe impl should show stripe profile");

        CallGraph paypalImpl = impls.stream()
                .filter(c -> "PayPalPaymentStrategy".equals(c.getClassName()))
                .findFirst().orElseThrow();
        Assertions.assertTrue(paypalImpl.getDesc().contains("[Profiles:"),
                "PayPal impl should show profile label");
        Assertions.assertTrue(paypalImpl.getDesc().contains("paypal"),
                "PayPal impl should show paypal profile");

        CallGraph cashImpl = impls.stream()
                .filter(c -> "CashPaymentStrategy".equals(c.getClassName()))
                .findFirst().orElseThrow();
        Assertions.assertTrue(cashImpl.getDesc() == null || !cashImpl.getDesc().contains("[Profiles:"),
                "Cash impl (no @Profile) should not have profile label");
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private CallGraph findChildByMethodName(CallGraph graph, String methodName) {
        if (graph.getCalledMethods() == null) return null;
        return graph.getCalledMethods().stream()
                .filter(c -> methodName.equals(c.getMethodName()))
                .findFirst()
                .orElse(null);
    }
}
