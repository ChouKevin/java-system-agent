package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.fixture.FixtureRepoLoader;
import com.java.system.agent.analysis.model.AnalysisErrorCode;
import com.java.system.agent.analysis.model.AnalysisMetadata;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisStatus;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaCallGraphAnalyzerErrorClassificationTest {

    private static final String FIXTURE = "spring-basic";
    private static final String CONTROLLER_SOURCE = "com/example/basic/BasicController.java";

    private JavaCallGraphAnalyzer analyzer;
    private ClassMetadataService classMetadataService;
    private ProjectParserService projectParserService;
    private FixtureRepoLoader fixtureRepoLoader;

    @BeforeEach
    void setUp() {
        SourceRootResolver sourceRootResolver = new SourceRootResolver();
        projectParserService = new ProjectParserService(sourceRootResolver);
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
        fixtureRepoLoader = new FixtureRepoLoader();
    }

    @Test
    void should_return_entrypoint_not_found_when_method_is_missing() {
        AnalysisResult<FlattenedCallGraph> result = analyzer.analyzeFlattenedResult(
                fixtureRepoLoader.fixtureRoot(FIXTURE),
                fixtureRepoLoader.sourceFile(FIXTURE, CONTROLLER_SOURCE),
                "missingMethod",
                AnalysisMetadata.now(FIXTURE, "com.example.basic", "BasicController", "missingMethod"));

        assertEquals(AnalysisStatus.FAILED, result.status());
        assertEquals(AnalysisErrorCode.ENTRYPOINT_NOT_FOUND, result.errors().get(0).code());
    }

    @Test
    void should_return_parse_failed_when_source_is_not_java(@TempDir Path repoRoot) throws IOException {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/Broken.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "this is not java {{{");

        AnalysisResult<FlattenedCallGraph> result = analyzer.analyzeFlattenedResult(
                repoRoot,
                repoRoot.relativize(sourceFile).toString(),
                "run",
                AnalysisMetadata.now("broken-repo", "com.example", "Broken", "run"));

        assertEquals(AnalysisStatus.FAILED, result.status());
        assertEquals(AnalysisErrorCode.PARSE_FAILED, result.errors().get(0).code());
    }

    @Test
    void should_return_internal_error_when_unrelated_exception_message_contains_not_found() {
        CallGraphBuilder failingBuilder = mock(CallGraphBuilder.class);
        DtoAnalyzer stubDtoAnalyzer = mock(DtoAnalyzer.class);
        when(stubDtoAnalyzer.analyze(any())).thenReturn(Map.of());
        when(failingBuilder.build(any(), any(), anyMap(), anyInt()))
                .thenThrow(new IllegalStateException("bean not found in registry"));
        JavaCallGraphAnalyzer failingAnalyzer = new JavaCallGraphAnalyzer(
                projectParserService,
                classMetadataService,
                stubDtoAnalyzer,
                failingBuilder,
                new CallGraphExplanationMapper(),
                4);

        AnalysisResult<FlattenedCallGraph> result = failingAnalyzer.analyzeFlattenedResult(
                fixtureRepoLoader.fixtureRoot(FIXTURE),
                fixtureRepoLoader.sourceFile(FIXTURE, CONTROLLER_SOURCE),
                "getBasic",
                AnalysisMetadata.now(FIXTURE, "com.example.basic", "BasicController", "getBasic"));

        assertEquals(AnalysisStatus.FAILED, result.status());
        assertEquals(AnalysisErrorCode.INTERNAL_ERROR, result.errors().get(0).code(),
                "unrelated exception must not be classified by message text");
    }
}
