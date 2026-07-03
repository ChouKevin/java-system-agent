package com.java.system.agent.api;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.callgraph.CallType;
import com.java.system.agent.analysis.model.ApiRef;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.CallNode;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.FlattenedMethodNode;
import com.java.system.agent.analysis.model.MethodId;
import com.java.system.agent.analysis.model.ResolutionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CallGraphControllerTest {

    @Mock
    private AnalysisService analysisService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new CallGraphController(analysisService),
                        new AnalysisController(analysisService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void callGraphShouldReturnExplainableAnalysisResult() throws Exception {
        ExplainableCallGraph graph = explainableGraph();
        when(analysisService.analyzeMethodExplainableStructured(
                "test-repo", "com.example", "OrderService", "createOrder"))
                .thenReturn(AnalysisResult.success(graph, null));

        mockMvc.perform(post("/analysis/call-graph/test-repo")
                        .contentType("application/json")
                        .content(methodRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.root.methodName").value("createOrder"))
                .andExpect(jsonPath("$.data.nodes[0].methodId.className").value("OrderService"))
                .andExpect(jsonPath("$.data.edges[0].resolutionStrategy").value("SAME_CLASS_METHOD"))
                .andExpect(jsonPath("$.data.edges[0].confidence").value(0.9))
                .andExpect(jsonPath("$.data.legacyFlattened.rootSignature").value("OrderService#createOrder"));

        verify(analysisService).analyzeMethodExplainableStructured(
                "test-repo", "com.example", "OrderService", "createOrder");
    }

    @Test
    void callGraphFlattenShouldReturnLegacyFlattenedGraph() throws Exception {
        FlattenedCallGraph flattenedGraph = flattenedGraph();
        when(analysisService.analyzeMethod("test-repo", "com.example", "OrderService", "createOrder"))
                .thenReturn(flattenedGraph);

        mockMvc.perform(post("/analysis/call-graph/test-repo/flatten")
                        .contentType("application/json")
                        .content(methodRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootSignature").value("OrderService#createOrder"))
                .andExpect(jsonPath("$.methods[0].methodName").value("createOrder"));

        verify(analysisService).analyzeMethod("test-repo", "com.example", "OrderService", "createOrder");
    }

    @Test
    void apiCallGraphShouldReturnExplainableAnalysisResultWhenApiExists() throws Exception {
        ExplainableCallGraph graph = explainableGraph();
        when(analysisService.lookupApi("/orders", "POST"))
                .thenReturn(List.of(new ApiRef("test-repo", "com.example", "OrderController", "createOrder")));
        when(analysisService.analyzeMethodExplainableStructured(
                "test-repo", "com.example", "OrderController", "createOrder"))
                .thenReturn(AnalysisResult.success(graph, null));

        mockMvc.perform(post("/analysis/api-call-graph")
                        .contentType("application/json")
                        .content("""
                                {
                                  "apiPath": "/orders",
                                  "httpMethod": "POST"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.root.methodName").value("createOrder"));

        verify(analysisService).lookupApi("/orders", "POST");
        verify(analysisService).analyzeMethodExplainableStructured(
                "test-repo", "com.example", "OrderController", "createOrder");
    }

    @Test
    void apiCallGraphShouldReturnNotFoundWhenApiDoesNotExist() throws Exception {
        when(analysisService.lookupApi("/missing", "GET")).thenReturn(List.of());

        mockMvc.perform(post("/analysis/api-call-graph")
                        .contentType("application/json")
                        .content("""
                                {
                                  "apiPath": "/missing",
                                  "httpMethod": "GET"
                                }
                                """))
                .andExpect(status().isNotFound());

        verify(analysisService).lookupApi("/missing", "GET");
    }

    private String methodRequest() {
        return """
                {
                  "packageName": "com.example",
                  "className": "OrderService",
                  "methodSignature": "createOrder"
                }
                """;
    }

    private ExplainableCallGraph explainableGraph() {
        MethodId root = new MethodId("test-repo", "com.example", "OrderService", "createOrder", List.of());
        MethodId callee = new MethodId("test-repo", "com.example", "OrderRepository", "save", List.of());
        CallNode rootNode = new CallNode(
                root,
                "com.example.OrderService#createOrder",
                CallType.INTERNAL_SERVICE,
                "src/main/java/com/example/OrderService.java",
                10,
                20,
                Map.of(),
                "void createOrder() {}");
        CallNode calleeNode = new CallNode(
                callee,
                "com.example.OrderRepository#save",
                CallType.DATA_ACCESS,
                "src/main/java/com/example/OrderRepository.java",
                5,
                8,
                Map.of(),
                "void save() {}");
        CallEdge edge = new CallEdge(
                root,
                callee,
                "save",
                null,
                ResolutionStrategy.SAME_CLASS_METHOD,
                0.9,
                List.of(),
                List.of());
        return new ExplainableCallGraph(
                root,
                List.of(rootNode, calleeNode),
                List.of(edge),
                Map.of("OrderRepository", "com.example.OrderRepository"),
                flattenedGraph());
    }

    private FlattenedCallGraph flattenedGraph() {
        FlattenedMethodNode methodNode = FlattenedMethodNode.builder()
                .signature("com.example.OrderService#createOrder")
                .className("OrderService")
                .methodName("createOrder")
                .callType(CallType.INTERNAL_SERVICE)
                .callees(List.of("com.example.OrderRepository#save"))
                .build();
        return FlattenedCallGraph.builder()
                .rootSignature("OrderService#createOrder")
                .methods(List.of(methodNode))
                .relatedClasses(Map.of("OrderRepository", "com.example.OrderRepository"))
                .build();
    }
}
