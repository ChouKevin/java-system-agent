package com.java.system.agent.analysis.callgraph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.java.system.agent.analysis.fixture.FixtureRepoLoader;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtoAnalyzerTest {

    private static final String FIXTURE = "dto-collision";

    private DtoAnalyzer dtoAnalyzer;
    private Path fixtureRoot;
    private JavaParser parser;

    @BeforeEach
    void setUp() {
        dtoAnalyzer = new DtoAnalyzer();
        fixtureRoot = new FixtureRepoLoader().fixtureRoot(FIXTURE);
        parser = new ProjectParserService(new SourceRootResolver()).createParser(fixtureRoot);
    }

    @Test
    void should_collect_parameter_dtos_when_return_type_is_unresolvable() throws IOException {
        MethodDeclaration method = findMethod("broken");

        Map<String, String> result = dtoAnalyzer.analyze(method);

        assertTrue(result.containsKey("Order"),
                "parameter DTO should survive unresolvable return type, got: " + result.keySet());
        assertTrue(result.get("Order").contains("alphaField"));
    }

    @Test
    void should_keep_both_dtos_when_simple_names_collide() throws IOException {
        MethodDeclaration method = findMethod("duplicate");

        Map<String, String> result = dtoAnalyzer.analyze(method);

        assertEquals(2, result.size(), "both Order DTOs should be kept, got: " + result.keySet());
        assertTrue(result.containsKey("com.example.dto.a.Order"));
        assertTrue(result.containsKey("com.example.dto.b.Order"));
        assertTrue(result.get("com.example.dto.a.Order").contains("alphaField"));
        assertTrue(result.get("com.example.dto.b.Order").contains("betaField"));
    }

    private MethodDeclaration findMethod(String methodName) throws IOException {
        Path sourceFile = fixtureRoot.resolve(
                Paths.get("src", "main", "java", "com", "example", "dto", "DtoEndpoint.java"));
        CompilationUnit cu = parser.parse(sourceFile).getResult()
                .orElseThrow(() -> new IllegalStateException("Failed to parse fixture: " + sourceFile));
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(candidate -> candidate.getNameAsString().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Method not found: " + methodName));
    }
}
