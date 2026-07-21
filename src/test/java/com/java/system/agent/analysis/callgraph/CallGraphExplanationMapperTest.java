package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.CallNode;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.MethodId;
import com.java.system.agent.analysis.model.ResolutionStrategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class CallGraphExplanationMapperTest {

    private final CallGraphExplanationMapper mapper = new CallGraphExplanationMapper();

    @Test
    void should_allow_legacy_call_node_constructor_without_source_location() {
        CallNode node = new CallNode(
                new MethodId("demo-repo", "com.example", "BasicService", "getBasic", List.of()),
                "com.example.BasicService#getBasic",
                CallType.INTERNAL_SERVICE,
                Map.of(),
                "String getBasic() {}");

        Assertions.assertNull(node.sourceFile());
        Assertions.assertNull(node.startLine());
        Assertions.assertNull(node.endLine());
    }

    @Test
    void should_copy_node_source_location() {
        CallGraph service = CallGraph.builder()
                .signature("com.example.BasicService#public String getBasic(Long id)")
                .className("BasicService")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERNAL_SERVICE)
                .sourceFile("src/main/java/com/example/BasicService.java")
                .startLine(15)
                .endLine(24)
                .build();

        ExplainableCallGraph graph = mapper.map("demo-repo", service, null);

        CallNode node = graph.nodes().get(0);
        Assertions.assertEquals("src/main/java/com/example/BasicService.java", node.sourceFile());
        Assertions.assertEquals(15, node.startLine());
        Assertions.assertEquals(24, node.endLine());
    }

    @Test
    void should_copy_edge_source_file_and_line_number() {
        CallResolutionEvidence evidence = new CallResolutionEvidence(
                ResolutionStrategy.SPRING_BEAN_BY_TYPE,
                0.95,
                List.of("RECEIVER_FIELD_TYPE"),
                List.of(),
                "src/main/java/com/example/BasicService.java",
                42);
        CallGraph repository = CallGraph.builder()
                .signature("com.example.BasicRepository#String findName(Long id)")
                .className("BasicRepository")
                .packagePath("com.example")
                .methodName("findName")
                .callType(CallType.DATA_ACCESS)
                .resolutionEvidence(evidence)
                .build();
        CallGraph service = CallGraph.builder()
                .signature("com.example.BasicService#public String getBasic(Long id)")
                .className("BasicService")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERNAL_SERVICE)
                .calledMethods(List.of(repository))
                .build();

        ExplainableCallGraph graph = mapper.map("demo-repo", service, null);

        CallEdge edge = graph.edges().get(0);
        Assertions.assertEquals("src/main/java/com/example/BasicService.java", edge.sourceFile());
        Assertions.assertEquals(42, edge.lineNumber());
    }

    @Test
    void should_map_interface_single_implementation_edge() {
        CallGraph implementation = CallGraph.builder()
                .signature("com.example.BasicServiceImpl#public String getBasic(Long id)")
                .className("BasicServiceImpl")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERNAL_SERVICE)
                .build();
        CallGraph interfaceNode = CallGraph.builder()
                .signature("com.example.BasicService#public String getBasic(Long id)")
                .className("BasicService")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERFACE)
                .calledMethods(List.of(implementation))
                .build();
        CallGraph root = CallGraph.builder()
                .signature("com.example.BasicController#public String getBasic(Long id)")
                .className("BasicController")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERNAL_CONTROLLER)
                .calledMethods(List.of(interfaceNode))
                .build();

        ExplainableCallGraph graph = mapper.map("demo-repo", root, null);

        Assertions.assertEquals(3, graph.nodes().size());
        Assertions.assertEquals(2, graph.edges().size());
        CallEdge interfaceEdge = graph.edges().get(1);
        Assertions.assertEquals(ResolutionStrategy.INTERFACE_SINGLE_IMPL, interfaceEdge.resolutionStrategy());
        Assertions.assertEquals(0.85, interfaceEdge.confidence());
        Assertions.assertTrue(interfaceEdge.warnings().isEmpty());
    }

    @Test
    void should_map_data_access_edge_as_mybatis_mapper() {
        CallGraph dataAccess = CallGraph.builder()
                .signature("com.example.BasicRepository#String findName(Long id)")
                .className("BasicRepository")
                .packagePath("com.example")
                .methodName("findName")
                .callType(CallType.DATA_ACCESS)
                .code("select name from basic where id = #{id}")
                .build();
        CallGraph service = CallGraph.builder()
                .signature("com.example.BasicService#public String getBasic(Long id)")
                .className("BasicService")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERNAL_SERVICE)
                .calledMethods(List.of(dataAccess))
                .build();

        ExplainableCallGraph graph = mapper.map("demo-repo", service, null);

        Assertions.assertEquals(1, graph.edges().size());
        CallEdge edge = graph.edges().get(0);
        Assertions.assertEquals(ResolutionStrategy.MYBATIS_MAPPER, edge.resolutionStrategy());
        Assertions.assertEquals(0.90, edge.confidence());
        Assertions.assertTrue(edge.warnings().isEmpty());
    }

    @Test
    void should_copy_explicit_evidence_to_edge() {
        CallResolutionEvidence evidence = new CallResolutionEvidence(
                ResolutionStrategy.SPRING_BEAN_BY_TYPE,
                0.95,
                List.of("RECEIVER_FIELD_TYPE", "TARGET_METHOD_FOUND"),
                List.of("Ambiguous bean type narrowed by field"),
                12);
        CallGraph repository = CallGraph.builder()
                .signature("com.example.BasicRepository#String findName(Long id)")
                .className("BasicRepository")
                .packagePath("com.example")
                .methodName("findName")
                .callType(CallType.DATA_ACCESS)
                .resolutionEvidence(evidence)
                .build();
        CallGraph service = CallGraph.builder()
                .signature("com.example.BasicService#public String getBasic(Long id)")
                .className("BasicService")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERNAL_SERVICE)
                .calledMethods(List.of(repository))
                .build();

        ExplainableCallGraph graph = mapper.map("demo-repo", service, null);

        CallEdge edge = graph.edges().get(0);
        Assertions.assertEquals(ResolutionStrategy.SPRING_BEAN_BY_TYPE, edge.resolutionStrategy());
        Assertions.assertEquals(0.95, edge.confidence());
        Assertions.assertEquals(List.of("RECEIVER_FIELD_TYPE", "TARGET_METHOD_FOUND"), edge.evidence());
        Assertions.assertEquals(List.of("Ambiguous bean type narrowed by field"), edge.warnings());
        Assertions.assertEquals(12, edge.lineNumber());
    }

    @Test
    void should_map_null_explicit_evidence_lists_to_empty_lists() {
        CallResolutionEvidence evidence = new CallResolutionEvidence(
                ResolutionStrategy.SPRING_BEAN_BY_TYPE,
                0.95,
                null,
                null,
                12);
        CallGraph repository = CallGraph.builder()
                .signature("com.example.BasicRepository#String findName(Long id)")
                .className("BasicRepository")
                .packagePath("com.example")
                .methodName("findName")
                .callType(CallType.DATA_ACCESS)
                .resolutionEvidence(evidence)
                .build();
        CallGraph service = CallGraph.builder()
                .signature("com.example.BasicService#public String getBasic(Long id)")
                .className("BasicService")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERNAL_SERVICE)
                .calledMethods(List.of(repository))
                .build();

        ExplainableCallGraph graph = mapper.map("demo-repo", service, null);

        CallEdge edge = graph.edges().get(0);
        Assertions.assertTrue(edge.evidence().isEmpty());
        Assertions.assertTrue(edge.warnings().isEmpty());
    }

    @Test
    void should_use_parsed_method_name_for_call_expression() {
        CallGraph dataAccess = CallGraph.builder()
                .signature("com.example.BasicRepository#String findName(Long id)")
                .className("BasicRepository")
                .packagePath("com.example")
                .callType(CallType.DATA_ACCESS)
                .build();
        CallGraph service = CallGraph.builder()
                .signature("com.example.BasicService#public String getBasic(Long id)")
                .className("BasicService")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERNAL_SERVICE)
                .calledMethods(List.of(dataAccess))
                .build();

        ExplainableCallGraph graph = mapper.map("demo-repo", service, null);

        Assertions.assertEquals(1, graph.edges().size());
        CallEdge edge = graph.edges().get(0);
        Assertions.assertEquals("findName", edge.callExpression());
    }

    @Test
    void should_keep_overloaded_methods_as_distinct_nodes() {
        CallGraph findByCode = CallGraph.builder()
                .signature("com.example.BasicRepository#String find(String code)")
                .className("BasicRepository")
                .packagePath("com.example")
                .methodName("find")
                .callType(CallType.DATA_ACCESS)
                .build();
        CallGraph findById = CallGraph.builder()
                .signature("com.example.BasicRepository#String find(Long id)")
                .className("BasicRepository")
                .packagePath("com.example")
                .methodName("find")
                .callType(CallType.DATA_ACCESS)
                .build();
        CallGraph service = CallGraph.builder()
                .signature("com.example.BasicService#public String getBasic(Long id)")
                .className("BasicService")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERNAL_SERVICE)
                .calledMethods(List.of(findByCode, findById))
                .build();

        ExplainableCallGraph graph = mapper.map("demo-repo", service, null);

        Assertions.assertEquals(3, graph.nodes().size());
        Assertions.assertTrue(graph.nodes().stream()
                .anyMatch(node -> node.methodId().parameterTypes().equals(List.of("String"))));
        Assertions.assertTrue(graph.nodes().stream()
                .anyMatch(node -> node.methodId().parameterTypes().equals(List.of("Long"))));
    }

    @Test
    void should_map_unresolved_edge_with_warning() {
        CallGraph unresolved = CallGraph.builder()
                .methodName("missingCall")
                .callType(CallType.UNRESOLVED)
                .build();
        CallGraph root = CallGraph.builder()
                .signature("com.example.BasicService#public String getBasic(Long id)")
                .className("BasicService")
                .packagePath("com.example")
                .methodName("getBasic")
                .callType(CallType.INTERNAL_SERVICE)
                .calledMethods(List.of(unresolved))
                .build();

        ExplainableCallGraph graph = mapper.map("demo-repo", root, null);

        Assertions.assertEquals(1, graph.edges().size());
        CallEdge edge = graph.edges().get(0);
        Assertions.assertEquals(ResolutionStrategy.UNRESOLVED, edge.resolutionStrategy());
        Assertions.assertEquals(0.10, edge.confidence());
        Assertions.assertEquals(List.of("FALLBACK_CALL_TYPE_UNRESOLVED"), edge.evidence());
        Assertions.assertEquals(List.of("Call could not be resolved"), edge.warnings());
    }

    @Test
    void should_return_empty_graph_when_root_is_null() {
        ExplainableCallGraph graph = mapper.map("demo-repo", null, null);

        Assertions.assertNull(graph.root());
        Assertions.assertTrue(graph.nodes().isEmpty());
        Assertions.assertTrue(graph.edges().isEmpty());
        Assertions.assertTrue(graph.relatedClasses().isEmpty());
    }
}
