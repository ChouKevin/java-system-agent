package com.java.system.agent.analysis.callgraph;

import com.java.system.agent.analysis.model.AnalysisMetadata;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisStatus;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlattenedWarningTest {

    @TempDir
    private Path repoRoot;

    private JavaCallGraphAnalyzer analyzer;
    private CallGraphBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        Path sourceDir = repoRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("A.java"),
                "package com.example; public class A { public void m() {} }");

        SourceRootResolver sourceRootResolver = new SourceRootResolver();
        ProjectParserService projectParserService = new ProjectParserService(sourceRootResolver);
        ClassMetadataService classMetadataService = new ClassMetadataService(
                new MapperXmlSqlExtractor(sourceRootResolver), projectParserService, sourceRootResolver);
        builder = mock(CallGraphBuilder.class);
        analyzer = new JavaCallGraphAnalyzer(
                projectParserService,
                classMetadataService,
                new DtoAnalyzer(),
                builder,
                new CallGraphExplanationMapper(),
                3);
    }

    @Test
    void flattenedResultReportsPartialWhenRawTreeContainsUnresolvedCall() {
        CallGraph root = CallGraph.builder()
                .signature("com.example.A#m()")
                .className("A")
                .methodName("m")
                .callType(CallType.INTERNAL_SERVICE)
                .calledMethods(List.of(CallGraph.leaf(null, null, "mystery", CallType.UNRESOLVED, null)))
                .build();
        when(builder.build(any(), any(), anyMap(), anyInt())).thenReturn(root);

        AnalysisResult<FlattenedCallGraph> result = analyzer.analyzeFlattenedResult(
                repoRoot,
                "src/main/java/com/example/A.java",
                "m",
                AnalysisMetadata.now("test", "com.example", "A", "m"));

        assertThat(result.status()).isEqualTo(AnalysisStatus.PARTIAL);
        assertThat(result.warnings())
                .singleElement()
                .satisfies(warning -> {
                    assertThat(warning.code()).isEqualTo("UNRESOLVED_CALL");
                    assertThat(warning.location()).isEqualTo("mystery");
                });
    }
}
