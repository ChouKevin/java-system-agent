package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.FlattenedMethodNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class CallGraphVisitorTest {

    @Test
    public void testFlatten_SkipInterfaces() {
        // Build a graph: Service -> Interface -> Impl
        CallGraph impl = CallGraph.builder()
                .methodName("implMethod")
                .callType(CallType.INTERNAL_SERVICE) // Using service for simplicity in test
                .signature("Impl.implMethod")
                .build();

        CallGraph iface = CallGraph.builder()
                .methodName("ifaceMethod")
                .callType(CallType.INTERFACE)
                .signature("Iface.ifaceMethod")
                .calledMethods(List.of(impl)) // Interface calls Impl
                .build();

        CallGraph service = CallGraph.builder()
                .methodName("serviceMethod")
                .callType(CallType.INTERNAL_SERVICE)
                .signature("Service.serviceMethod")
                .calledMethods(List.of(iface)) // Service calls Interface
                .build();

        // 1. Flatten with skipInterfaces (Using passThroughType)
        GraphVisitorConfig configSkip = GraphVisitorConfig.builder()
                .keepType(CallType.INTERNAL_SERVICE)
                .passThroughType(CallType.INTERFACE)
                .build();
        
        FlattenedCallGraph result = CallGraphVisitor.flattenToOptimized(service, configSkip);
        
        // Assert Methods: Should contain Service and Impl only (Interface is skipped/passed through)
        Assertions.assertEquals(2, result.getMethods().size(), "Should contain Service and Impl only");
        Assertions.assertEquals("Service.serviceMethod", result.getMethods().get(0).getSignature());
        Assertions.assertEquals("Impl.implMethod", result.getMethods().get(1).getSignature());
        Assertions.assertFalse(result.getMethods().stream()
                .anyMatch(m -> "Iface.ifaceMethod".equals(m.getSignature())));
        
        // Assert Callees: Service -> Impl (Interface is bypassed)
        FlattenedMethodNode serviceNode = result.getMethods().get(0);
        Assertions.assertTrue(serviceNode.getCallees().contains("Impl.implMethod"),
                "Should have direct callee from Service to Impl");
    }

    @Test
    public void testFlatten_FilterByType() {
         // Build a graph: Service -> DataAccess -> StdLib
        CallGraph stdLib = CallGraph.builder()
                .methodName("listAdd")
                .callType(CallType.EXTERNAL_LIB)
                .signature("java.util.List.add")
                .build();

        CallGraph dao = CallGraph.builder()
                .methodName("findUser")
                .callType(CallType.DATA_ACCESS)
                .signature("UserMapper.findUser")
                .calledMethods(List.of(stdLib))
                .build();

        CallGraph service = CallGraph.builder()
                .methodName("getUser")
                .callType(CallType.INTERNAL_SERVICE)
                .signature("UserService.getUser")
                .calledMethods(List.of(dao))
                .build();

        // Filter: Only INTERNAL_SERVICE and DATA_ACCESS
        GraphVisitorConfig configKeep = GraphVisitorConfig.builder()
                .keepType(CallType.INTERNAL_SERVICE)
                .keepType(CallType.DATA_ACCESS)
                .build();

        FlattenedCallGraph result = CallGraphVisitor.flattenToOptimized(service, configKeep);
        
        // Assert Methods
        Assertions.assertEquals(2, result.getMethods().size());
        Assertions.assertTrue(result.getMethods().stream().anyMatch(n -> n.getCallType() == CallType.INTERNAL_SERVICE));
        Assertions.assertTrue(result.getMethods().stream().anyMatch(n -> n.getCallType() == CallType.DATA_ACCESS));
        Assertions.assertFalse(result.getMethods().stream().anyMatch(n -> n.getCallType() == CallType.EXTERNAL_LIB));
        
        // Assert Callees
        FlattenedMethodNode serviceNode = result.getMethods().stream()
                .filter(n -> "UserService.getUser".equals(n.getSignature()))
                .findFirst()
                .orElseThrow();
        FlattenedMethodNode daoNode = result.getMethods().stream()
                .filter(n -> "UserMapper.findUser".equals(n.getSignature()))
                .findFirst()
                .orElseThrow();
        
        Assertions.assertTrue(serviceNode.getCallees().contains("UserMapper.findUser"));
        Assertions.assertFalse(daoNode.getCallees().contains("java.util.List.add"));
    }

    @Test
    public void testFlatten_CodeInclusion() {
        // Build a graph with MQ type
        CallGraph mq = CallGraph.builder()
                .methodName("onMessage")
                .callType(CallType.MESSAGE_QUEUE)
                .signature("MqListener.onMessage")
                .code("public void onMessage(String msg) { ... }")
                .build();
        
        // Use default config
        GraphVisitorConfig config = GraphVisitorConfig.defaultConfig();
        FlattenedCallGraph result = CallGraphVisitor.flattenToOptimized(mq, config);
        
        Assertions.assertEquals(1, result.getMethods().size());
        Assertions.assertNotNull(result.getMethods().get(0).getCode(), "MQ listener should have code in flattened graph");
        Assertions.assertEquals("public void onMessage(String msg) { ... }", result.getMethods().get(0).getCode());
    }
}
