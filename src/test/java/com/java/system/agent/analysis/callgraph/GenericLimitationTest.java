package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.model.MethodRef;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.fixture.FixtureRepoLoader;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 泛型支援度測試
 *
 * 驗證 ScopeTypeResolver 的 stripGenerics 在 call graph 分析中的行為，
 * 涵蓋三種情境：
 * <ol>
 *   <li>巢狀泛型欄位 — 外層型別可解析，內層型別參數丟失</li>
 *   <li>泛型繼承鏈 — 父類方法找不到（T 未傳播），非泛型參數的方法可找到</li>
 *   <li>單層泛型欄位 — stripGenerics 後仍可正確定位 class</li>
 * </ol>
 *
 * 範例程式碼位於 {@code src/test/resources/fixtures/generic-limitation}
 */
public class GenericLimitationTest {

    private static final String FIXTURE = "generic-limitation";

    private TestRepoService service;

    @BeforeEach
    void setUp() {
        SourceRootResolver sourceRootResolver = new SourceRootResolver();
        ProjectParserService projectParserService = new ProjectParserService(sourceRootResolver);
        ClassMetadataService classMetadataService = new ClassMetadataService(
                new MapperXmlSqlExtractor(sourceRootResolver),
                new ProjectParserService(sourceRootResolver),
                sourceRootResolver);
        ScopeTypeResolver scopeTypeResolver = new ScopeTypeResolver();
        CallGraphBuilder callGraphBuilder = new CallGraphBuilder(
                new CallGraphClassifier(), classMetadataService, scopeTypeResolver);
        JavaCallGraphAnalyzer analyzer = new JavaCallGraphAnalyzer(
                projectParserService, classMetadataService, new DtoAnalyzer(), callGraphBuilder, 5);
        service = new TestRepoService(
                new FixtureRepoLoader().fixtureRoot(FIXTURE),
                new EntryPointCacheService(projectParserService, sourceRootResolver),
                analyzer);
    }

    // =========================================================================
    // Scenario 1: 巢狀泛型
    // =========================================================================

    @Nested
    class NestedGenericTest {

        @Test
        void direct_field_should_resolve_correctly() {
            // sender 欄位型別為 NotificationSender（無泛型），應正確解析
            CallGraph graph = service.readJavaSource(
                    new MethodRef("com.example.generic", "NestedGenericCaller", "directCall"));

            assertNotNull(graph);

            CallGraph sendCall = findChild(graph, "send");
            assertNotNull(sendCall, "sender.send() should be found");
            assertEquals("NotificationSender", sendCall.getClassName(),
                    "Direct field should resolve to NotificationSender");
        }

        @Test
        void nested_generic_field_resolves_to_outer_type_only() {
            // senderMap 型別為 Map<String, List<NotificationSender>>
            // stripGenerics → "Map" → 找不到專案內的 Map class → EXTERNAL_LIB
            CallGraph graph = service.readJavaSource(
                    new MethodRef("com.example.generic", "NestedGenericCaller", "nestedGenericCall"));

            assertNotNull(graph);

            CallGraph getCall = findChild(graph, "get");
            assertNotNull(getCall, "senderMap.get() should appear in call graph");
            assertEquals(CallType.EXTERNAL_LIB, getCall.getCallType(),
                    "Map is a JDK class — should be EXTERNAL_LIB, not resolved to NotificationSender");
        }
    }

    // =========================================================================
    // Scenario 2: 泛型繼承鏈
    // =========================================================================

    @Nested
    class GenericInheritanceTest {

        @Test
        void inherited_non_generic_method_falls_back() {
            // OrderCrudService extends AbstractCrudService<Order>
            // deleteById(Long) 定義在父類，子類未 override
            // 分析時以 OrderCrudService 為 target → findMethodInClass 找不到 → fallback
            CallGraph graph = service.readJavaSource(
                    new MethodRef("com.example.generic", "OrderCrudService", "processOrder"));

            assertNotNull(graph);

            CallGraph deleteCall = findChild(graph, "deleteById");
            assertNotNull(deleteCall, "deleteById() should appear in call graph");
            assertEquals(CallType.INTERNAL_SERVICE, deleteCall.getCallType(),
                    "Inherited method fallback should preserve parent's detected type (INTERNAL_SERVICE)");
            assertNotNull(deleteCall.getDesc());
            assertTrue(deleteCall.getDesc().contains("Method source not found"),
                    "Should indicate method was not found in the subclass");
        }

        @Test
        void inherited_generic_param_method_falls_back() {
            // save(T entity) — T 不會被傳播為 Order
            // 在 OrderCrudService 上找不到 save(Order) 的宣告 → 同樣 fallback
            CallGraph graph = service.readJavaSource(
                    new MethodRef("com.example.generic", "OrderCrudService", "processOrder"));

            assertNotNull(graph);

            CallGraph saveCall = findChild(graph, "save");
            assertNotNull(saveCall, "save() should appear in call graph");
            assertEquals(CallType.INTERNAL_SERVICE, saveCall.getCallType(),
                    "Inherited generic method should also fall back to INTERNAL_SERVICE");
            assertNotNull(saveCall.getDesc());
            assertTrue(saveCall.getDesc().contains("Method source not found"),
                    "Generic param method also falls into 'not found' fallback");
        }
    }

    // =========================================================================
    // Scenario 3: 單層泛型欄位
    // =========================================================================

    @Nested
    class SimpleGenericFieldTest {

        @Test
        void single_level_generic_field_resolves_class() {
            // crudService 型別為 AbstractCrudService<Order>
            // stripGenerics → "AbstractCrudService" → 找到 class → 可遞迴
            CallGraph graph = service.readJavaSource(
                    new MethodRef("com.example.generic", "GenericServiceCaller", "callGenericService"));

            assertNotNull(graph);

            CallGraph deleteCall = findChild(graph, "deleteById");
            assertNotNull(deleteCall, "crudService.deleteById() should be found");
            assertEquals("AbstractCrudService", deleteCall.getClassName(),
                    "Single-level generic should strip to AbstractCrudService and resolve");
            assertEquals(CallType.INTERNAL_SERVICE, deleteCall.getCallType(),
                    "AbstractCrudService has @Service — should be INTERNAL_SERVICE, not EXTERNAL_LIB");
        }
    }

    // =========================================================================

    private CallGraph findChild(CallGraph graph, String methodName) {
        if (graph.getCalledMethods() == null) return null;
        return graph.getCalledMethods().stream()
                .filter(c -> methodName.equals(c.getMethodName()))
                .findFirst()
                .orElse(null);
    }
}
