package com.java.system.agent.analysis.callgraph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.FlattenedMethodNode;
import com.java.system.agent.analysis.model.MethodRef;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CallGraphTest {

    private TestRepoService service;
    private TestRepoService serviceDepth3;

    @BeforeEach
    void setUp() {
        ProjectParserService projectParserService = new ProjectParserService(new SourceRootResolver());
        CallGraphClassifier classifier = new CallGraphClassifier();
        EntryPointCacheService entryPointCacheService = new EntryPointCacheService(projectParserService, new SourceRootResolver());
        DtoAnalyzer dtoAnalyzer = new DtoAnalyzer();

        ClassMetadataService classMetadataService = new ClassMetadataService(new MapperXmlSqlExtractor(new SourceRootResolver()), new ProjectParserService(new SourceRootResolver()), new SourceRootResolver());
        ScopeTypeResolver scopeTypeResolver = new ScopeTypeResolver();
        CallGraphBuilder callGraphBuilder = new CallGraphBuilder(classifier, classMetadataService, scopeTypeResolver);

        JavaCallGraphAnalyzer javaCallGraphAnalyzer = new JavaCallGraphAnalyzer(projectParserService, classMetadataService, dtoAnalyzer, callGraphBuilder, 5);
        service = new TestRepoService(entryPointCacheService, javaCallGraphAnalyzer);

        // maxDepth=3 for TRAVERSAL_CUTOFF tests
        ClassMetadataService cms3 = new ClassMetadataService(new MapperXmlSqlExtractor(new SourceRootResolver()), new ProjectParserService(new SourceRootResolver()), new SourceRootResolver());
        CallGraphBuilder cgb3 = new CallGraphBuilder(classifier, cms3, new ScopeTypeResolver());
        JavaCallGraphAnalyzer analyzer3 = new JavaCallGraphAnalyzer(new ProjectParserService(new SourceRootResolver()), cms3, new DtoAnalyzer(), cgb3, 3);
        serviceDepth3 = new TestRepoService(entryPointCacheService, analyzer3);
    }

    @Test
    public void testCallGraph() {
        
        CallGraph graph = service.readJavaSource(new MethodRef("com.example.service", "MainService", "entryPoint"));

        Assertions.assertNotNull(graph);
        Assertions.assertEquals("entryPoint", graph.getMethodName());
        Assertions.assertEquals("MainService", graph.getClassName());
        Assertions.assertEquals("com.example.service", graph.getPackagePath());

        List<CallGraph> children = graph.getCalledMethods();
        Assertions.assertNotNull(children);

        // Expected calls: helperService.doSomething(), myInterface.execute(),
        // recursiveMethod(0)

        // Check helperService.doSomething()
        CallGraph doSomething = children.stream()
                .filter(c -> c.getMethodName().equals("doSomething"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("doSomething not found"));

        Assertions.assertEquals("HelperService", doSomething.getClassName());
        Assertions.assertEquals(CallType.INTERNAL_SERVICE, doSomething.getCallType());

        // Check myInterface.execute()
        CallGraph execute = children.stream()
                .filter(c -> c.getMethodName().equals("execute"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("execute not found"));

        Assertions.assertEquals("MyInterface", execute.getClassName());
        Assertions.assertEquals(CallType.INTERFACE, execute.getCallType());

        // Check implementation of MyInterface
        Assertions.assertFalse(execute.getCalledMethods().isEmpty(), "Interface implementation not found");
        CallGraph impl = execute.getCalledMethods().get(0);
        Assertions.assertEquals("MyImpl", impl.getClassName());
        Assertions.assertEquals("execute", impl.getMethodName());

        // Check recursiveMethod
        CallGraph recursion = children.stream()
                .filter(c -> c.getMethodName().equals("recursiveMethod"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("recursiveMethod not found"));

        // Verify recursion handling (it should stop at some point)
        // Main -> recursizeMethod -> recursizeMethod -> ...
        // We set MAX_DEPTH = 5 in Analyzer.
        // It shouldn't crash.

        try {
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(graph));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testFallbackResolution() {
        CallGraph graph = service.readJavaSource(new MethodRef("com.example.fallback", "FallbackCaller", "entryPointFallback"));

        Assertions.assertNotNull(graph);
        List<CallGraph> children = graph.getCalledMethods();
        Assertions.assertNotNull(children);

        CallGraph interfaceNode = children.stream()
                .filter(c -> c.getMethodName().equals("doWork"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Fallback interface method not found"));

        Assertions.assertEquals(CallType.INTERFACE, interfaceNode.getCallType());
        Assertions.assertFalse(interfaceNode.getCalledMethods().isEmpty(), "Fallback implementation not found");
    }

    @Test
    public void testVisitedSignatureCollision() {
        CallGraph graph = service.readJavaSource(new MethodRef("com.example.visit", "CollisionCaller", "entry"));

        Assertions.assertNotNull(graph);
        List<CallGraph> children = graph.getCalledMethods();
        Assertions.assertNotNull(children);

        boolean alphaFound = children.stream()
                .anyMatch(c -> "AlphaService".equals(c.getClassName()) && "process".equals(c.getMethodName()));
        boolean betaFound = children.stream()
                .anyMatch(c -> "BetaService".equals(c.getClassName()) && "process".equals(c.getMethodName()));

        Assertions.assertTrue(alphaFound, "AlphaService.process not found");
        Assertions.assertTrue(betaFound, "BetaService.process not found");
    }
    @Test
    public void testSourceCodeExtraction() {
        // Verifies that the source code is correctly extracted for business methods.
        // This validates the logic in CallGraphBuilder where it calls method.toString() to capture source code.
        // It helps developers confirm that the AST parsing is retrieving the actual code block.

        CallGraph graph = service.readJavaSource(new MethodRef("com.example.service", "MainService", "entryPoint"));

        // Root should have code
        Assertions.assertNotNull(graph.getCode(), "Root method code should be present");
        Assertions.assertTrue(graph.getCode().contains("public void entryPoint()"), "Code content should match source");

        // Internal service call should have code
        CallGraph internalCall = graph.getCalledMethods().stream()
                .filter(c -> "HelperService".equals(c.getClassName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("HelperService not found"));
        
        Assertions.assertNotNull(internalCall.getCode(), "Internal service code should be present");
        Assertions.assertTrue(internalCall.getCode().contains("doSomething"), "Code content should contain method name");
    }

    @Test
    public void should_populateCalleesOnCutoffNode_when_depthReachesMax() {
        // With maxDepth=3, depths: 0, 1, 2, 3, ...
        //   depth 0: DepthController.entry → INTERNAL_CONTROLLER
        //   depth 1: DepthServiceA.processA → INTERNAL_SERVICE
        //   depth 2: DepthServiceB.processB → INTERNAL_SERVICE
        //   depth 3 (==maxDepth): DepthServiceC.processC → TRAVERSAL_CUTOFF with callees
        //   depth 4 (>maxDepth): DepthServiceD → NOT an entry, only callee sig
        FlattenedCallGraph flattened = serviceDepth3.readFlattened(
                new MethodRef("com.example.depth", "DepthController", "entry"));

        List<FlattenedMethodNode> methods = flattened.getMethods();

        Set<String> allSignatures = methods.stream()
                .map(FlattenedMethodNode::getSignature)
                .collect(Collectors.toSet());

        // Find the TRAVERSAL_CUTOFF node (DepthServiceA at depth 2)
        List<FlattenedMethodNode> cutoffNodes = methods.stream()
                .filter(m -> m.getCallType() == CallType.TRAVERSAL_CUTOFF)
                .collect(Collectors.toList());
        Assertions.assertFalse(cutoffNodes.isEmpty(), "Should have at least one TRAVERSAL_CUTOFF node");

        FlattenedMethodNode cutoff = cutoffNodes.get(0);
        Assertions.assertEquals("DepthServiceC", cutoff.getClassName());

        // TRAVERSAL_CUTOFF node should have callees populated (DepthServiceB.processB)
        Assertions.assertNotNull(cutoff.getCallees(), "TRAVERSAL_CUTOFF callees should not be null");
        Assertions.assertFalse(cutoff.getCallees().isEmpty(),
                "TRAVERSAL_CUTOFF node should have callee signatures populated");

        // Callee signatures should NOT appear as independent entries in methods list
        for (String calleeSig : cutoff.getCallees()) {
            Assertions.assertFalse(allSignatures.contains(calleeSig),
                    "Callee of TRAVERSAL_CUTOFF should NOT appear as an independent entry: " + calleeSig);
        }

        // TRAVERSAL_CUTOFF should include source code
        Assertions.assertNotNull(cutoff.getCode(), "TRAVERSAL_CUTOFF node should include source code");

        try {
            System.out.println("=== TRAVERSAL_CUTOFF Flattened Output ===");
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(flattened));
        } catch (Exception e) {
            // ignore
        }
    }

    @Test
    public void testMqListenerDetection() {
        CallGraph graph = service.readJavaSource(new MethodRef("com.example.listener", "MqListenerService", "onMessage"));

        Assertions.assertNotNull(graph);
        Assertions.assertEquals("onMessage", graph.getMethodName());
        Assertions.assertEquals("MqListenerService", graph.getClassName());
        Assertions.assertEquals(CallType.MESSAGE_QUEUE, graph.getCallType());
        
        // Should also have code
        Assertions.assertNotNull(graph.getCode());
        Assertions.assertTrue(graph.getCode().contains("@RabbitListener"));
    }
}
