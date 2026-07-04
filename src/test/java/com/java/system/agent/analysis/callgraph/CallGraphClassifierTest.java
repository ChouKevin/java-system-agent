package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.model.ClassMetadata;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class CallGraphClassifierTest {

    @Test
    void testLombokHeuristic() {
        CallGraphClassifier classifier = new CallGraphClassifier();

        // Test isLombokHeuristic directly (method name pattern matching)
        assertTrue(classifier.isLombokHeuristic("getSuccess"));
        assertTrue(classifier.isLombokHeuristic("setName"));
        assertTrue(classifier.isLombokHeuristic("isActive"));
        assertTrue(classifier.isLombokHeuristic("toString"));
        assertTrue(classifier.isLombokHeuristic("equals"));
        assertTrue(classifier.isLombokHeuristic("hashCode"));

        assertFalse(classifier.isLombokHeuristic("getEntryPointSummary".substring(0, 3)));
        assertFalse(classifier.isLombokHeuristic("process"));
    }

    @Test
    void testLombokAnnotation() {
        CallGraphClassifier classifier = new CallGraphClassifier();

        ClassMetadata dataClass = ClassMetadata.builder()
                .className("BonusSendResultDto")
                .packageName("com.java.system.agent.dto")
                .annotations(List.of("Data"))
                .implementedTypes(Collections.emptyList())
                .extendedTypes(Collections.emptyList())
                .methods(Collections.emptyList())
                .build();
        assertTrue(classifier.isLombokAnnotated(dataClass));

        ClassMetadata serviceClass = ClassMetadata.builder()
                .className("BonusService")
                .packageName("com.java.system.agent.service")
                .annotations(List.of("Service"))
                .implementedTypes(Collections.emptyList())
                .extendedTypes(Collections.emptyList())
                .methods(Collections.emptyList())
                .build();
        assertFalse(classifier.isLombokAnnotated(serviceClass));
        assertTrue(classifier.shouldRecurse(serviceClass));
    }

    @Test
    void testFluentHeuristic() {
        CallGraphClassifier classifier = new CallGraphClassifier();

        ClassMetadata fluentClass = ClassMetadata.builder()
                .className("FluentDto")
                .packageName("com.java.system.agent.dto")
                .annotations(List.of("Accessors"))
                .hasFluentAccessors(true)
                .implementedTypes(Collections.emptyList())
                .extendedTypes(Collections.emptyList())
                .methods(Collections.emptyList())
                .build();

        assertTrue(classifier.isFluentAccessors(fluentClass));

        ClassMetadata nonFluentClass = ClassMetadata.builder()
                .className("NormalDto")
                .packageName("com.java.system.agent.dto")
                .annotations(List.of("Data"))
                .hasFluentAccessors(false)
                .implementedTypes(Collections.emptyList())
                .extendedTypes(Collections.emptyList())
                .methods(Collections.emptyList())
                .build();

        assertFalse(classifier.isFluentAccessors(nonFluentClass));
    }

    @Test
    void testBuilderHeuristic() {
        CallGraphClassifier classifier = new CallGraphClassifier();

        ClassMetadata builderClass = ClassMetadata.builder()
                .className("UserRequest")
                .packageName("com.java.system.agent.dto")
                .annotations(List.of("Builder"))
                .implementedTypes(Collections.emptyList())
                .extendedTypes(Collections.emptyList())
                .methods(Collections.emptyList())
                .build();

        assertTrue(classifier.isBuilderAnnotated(builderClass));
    }

    @Test
    void should_classify_common_spring_data_repositories_as_data_access() {
        CallGraphClassifier classifier = new CallGraphClassifier();
        List<String> repositoryTypes = List.of(
                "JpaRepository<OrderEntity, String>",
                "CrudRepository<OrderEntity, String>",
                "PagingAndSortingRepository<OrderEntity, String>",
                "MongoRepository<OrderEntity, String>",
                "R2dbcRepository<OrderEntity, String>");

        for (String repositoryType : repositoryTypes) {
            ClassMetadata metadata = ClassMetadata.builder()
                    .className("OrderRepository")
                    .packageName("com.example.persistence")
                    .isInterface(true)
                    .annotations(List.of())
                    .implementedTypes(List.of())
                    .extendedTypes(List.of(repositoryType))
                    .methods(List.of())
                    .build();

            assertEquals(CallType.DATA_ACCESS, classifier.detectType(metadata), repositoryType);
            assertTrue(classifier.isDatabaseLayer(metadata), repositoryType);
        }
    }
}
