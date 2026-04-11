package com.java.system.agent.analysis.callgraph;

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
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the MyBatis-Plus {@code baseMapper} pattern.
 *
 * <p>Verifies that when a ServiceImpl calls {@code baseMapper.someMethod()},
 * the call graph correctly:
 * <ol>
 *   <li>Resolves {@code baseMapper} type from {@code ServiceImpl<M, E>}'s first generic type arg</li>
 *   <li>Identifies the resolved mapper as DATA_ACCESS</li>
 *   <li>Attaches the SQL defined in the XML mapper file</li>
 *   <li>Surfaces the SQL in the flattened response (via {@code code} field)</li>
 * </ol>
 *
 * <p>Uses {@code repos/test/src/main/java/com/example/mybatisplus/OrderServiceImpl.java}
 * which extends {@code ServiceImpl<OrderMapper, Order>} and calls
 * {@code baseMapper.findByOrderNo(orderNo)}.
 * The SQL for {@code findByOrderNo} is defined in
 * {@code repos/test/src/main/resources/mapper/OrderMapper.xml}.
 */
public class BaseMapperCallGraphTest {

    private static final Path TEST_REPO = Paths.get("repos/test");
    private static final String SERVICE_FILE =
            "src/main/java/com/example/mybatisplus/OrderServiceImpl.java";

    private JavaCallGraphAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        ClassMetadataService classMetadataService = new ClassMetadataService(new MapperXmlSqlExtractor(new SourceRootResolver()), new ProjectParserService(new SourceRootResolver()), new SourceRootResolver());
        ScopeTypeResolver scopeTypeResolver = new ScopeTypeResolver();
        CallGraphBuilder callGraphBuilder = new CallGraphBuilder(
                new CallGraphClassifier(), classMetadataService, scopeTypeResolver);

        analyzer = new JavaCallGraphAnalyzer(
                new ProjectParserService(new SourceRootResolver()), classMetadataService, new DtoAnalyzer(), callGraphBuilder, 3);
    }

    @Test
    void should_resolve_baseMapper_call_as_DATA_ACCESS_with_xml_sql() {
        // OrderServiceImpl.getOrderByNo calls baseMapper.findByOrderNo(orderNo)
        // baseMapper type = OrderMapper (from ServiceImpl<OrderMapper, Order>)
        // SQL defined in OrderMapper.xml
        CallGraph graph = analyzer.analyze(TEST_REPO, SERVICE_FILE, "getOrderByNo");

        assertNotNull(graph, "Call graph should be built");

        CallGraph mapperCall = findChildByMethodName(graph, "findByOrderNo");
        assertNotNull(mapperCall,
                "findByOrderNo call on baseMapper should appear as a child node");
        assertEquals("OrderMapper", mapperCall.getClassName(),
                "Should resolve to OrderMapper — the M in ServiceImpl<M, E>");
        assertEquals(CallType.DATA_ACCESS, mapperCall.getCallType(),
                "Mapper method should be DATA_ACCESS");
        assertNotNull(mapperCall.getCode(),
                "SQL should be extracted from OrderMapper.xml");
        assertTrue(mapperCall.getCode().contains("orders"),
                "SQL should reference the orders table");
        assertTrue(mapperCall.getCode().contains("order_no"),
                "SQL should contain the order_no column");
    }

    @Test
    void should_surface_xml_sql_in_flattened_code_field() {
        // This is the bug the user hit: CallGraphVisitor was using node.getCode() for
        // DATA_ACCESS nodes — SQL is stored in node.getCode() (sql merged into code).
        // Result: "code": null in the flattened response even when SQL exists.
        CallGraph graph = analyzer.analyze(TEST_REPO, SERVICE_FILE, "getOrderByNo");

        assertNotNull(graph);
        FlattenedCallGraph flattened = CallGraphVisitor.flattenToOptimized(graph, GraphVisitorConfig.defaultConfig());

        FlattenedMethodNode mapperNode = flattened.getMethods().stream()
                .filter(n -> "findByOrderNo".equals(n.getMethodName()))
                .findFirst()
                .orElse(null);

        assertNotNull(mapperNode, "findByOrderNo should appear in the flattened result");
        assertEquals(CallType.DATA_ACCESS, mapperNode.getCallType());
        assertNotNull(mapperNode.getCode(),
                "SQL from XML mapper must appear in the flattened 'code' field");
        assertTrue(mapperNode.getCode().contains("orders"),
                "Flattened code should contain the SQL table name");
    }

    @Test
    void should_not_recurse_into_mapper_data_access_node() {
        CallGraph graph = analyzer.analyze(TEST_REPO, SERVICE_FILE, "getOrderByNo");

        assertNotNull(graph);
        CallGraph mapperCall = findChildByMethodName(graph, "findByOrderNo");
        assertNotNull(mapperCall);

        List<CallGraph> children = mapperCall.getCalledMethods();
        assertTrue(children == null || children.isEmpty(),
                "DATA_ACCESS mapper node should be a leaf with no children");
    }

    @Test
    void should_show_sql_when_mapper_itself_is_the_root_entry_point() {
        // Simulates the user querying the mapper directly:
        //   packageName = "com.example.mybatisplus"
        //   className   = "OrderMapper"
        //   methodSignature = "findByOrderNo"
        // The root node IS the DATA_ACCESS mapper method, not a service calling it.
        String mapperFile = "src/main/java/com/example/mybatisplus/OrderMapper.java";
        CallGraph graph = analyzer.analyze(TEST_REPO, mapperFile, "findByOrderNo");

        assertNotNull(graph);
        assertEquals(CallType.DATA_ACCESS, graph.getCallType(),
                "Root mapper method should be DATA_ACCESS");
        assertNotNull(graph.getCode(),
                "SQL should be fetched from OrderMapper.xml for the root DATA_ACCESS node");
        assertTrue(graph.getCode().contains("orders"),
                "SQL should reference the orders table");

        // Verify the flattened code field also contains the SQL (not just the abstract declaration)
        FlattenedCallGraph flattened = CallGraphVisitor.flattenToOptimized(graph, GraphVisitorConfig.defaultConfig());
        FlattenedMethodNode rootNode = flattened.getMethods().stream()
                .filter(n -> "findByOrderNo".equals(n.getMethodName()))
                .findFirst()
                .orElse(null);
        assertNotNull(rootNode);
        assertNotNull(rootNode.getCode(), "Flattened code should contain SQL for DATA_ACCESS root");
        assertTrue(rootNode.getCode().contains("orders"),
                "Flattened code should reference the orders table");
    }

    // -------------------------------------------------------------------------

    private CallGraph findChildByMethodName(CallGraph graph, String methodName) {
        if (graph.getCalledMethods() == null) return null;
        return graph.getCalledMethods().stream()
                .filter(c -> methodName.equals(c.getMethodName()))
                .findFirst()
                .orElse(null);
    }
}
