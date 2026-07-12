package com.java.system.agent.analysis.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 測試 ProjectParserService
 * JavaParser 非執行緒安全，服務只快取 ParserConfiguration，每次呼叫建立新 parser
 */
class ProjectParserServiceTest {

    private ProjectParserService service;

    @BeforeEach
    void setUp() {
        service = new ProjectParserService(new SourceRootResolver());
    }

    @Test
    void should_return_distinct_parser_instances_when_same_repo_creates_twice(@TempDir Path tempDir)
            throws Exception {
        Path repoRoot = tempDir.resolve("test-repo");
        Files.createDirectories(repoRoot.resolve("src/main/java"));

        JavaParser parser1 = service.createParser(repoRoot);
        JavaParser parser2 = service.createParser(repoRoot);

        assertNotSame(parser1, parser2, "Each call must create a fresh JavaParser instance");
    }

    @Test
    void should_share_cached_configuration_when_same_repo_creates_twice(@TempDir Path tempDir)
            throws Exception {
        Path repoRoot = tempDir.resolve("test-repo");
        Files.createDirectories(repoRoot.resolve("src/main/java"));

        JavaParser parser1 = service.createParser(repoRoot);
        JavaParser parser2 = service.createParser(repoRoot);

        assertSame(parser1.getParserConfiguration(), parser2.getParserConfiguration(),
                "Same repo should share the cached ParserConfiguration");
    }

    @Test
    void should_use_different_configurations_when_repos_differ(@TempDir Path tempDir) throws Exception {
        Path repo1 = tempDir.resolve("repo1");
        Path repo2 = tempDir.resolve("repo2");
        Files.createDirectories(repo1.resolve("src/main/java"));
        Files.createDirectories(repo2.resolve("src/main/java"));

        JavaParser parser1 = service.createParser(repo1);
        JavaParser parser2 = service.createParser(repo2);

        assertNotSame(parser1.getParserConfiguration(), parser2.getParserConfiguration(),
                "Different repos should have different configurations");
    }

    @Test
    void should_rebuild_configuration_when_invalidated(@TempDir Path tempDir) throws Exception {
        Path repoRoot = tempDir.resolve("test-repo");
        Files.createDirectories(repoRoot.resolve("src/main/java"));

        JavaParser parser1 = service.createParser(repoRoot);

        service.invalidate(repoRoot);
        JavaParser parser2 = service.createParser(repoRoot);

        assertNotSame(parser1.getParserConfiguration(), parser2.getParserConfiguration(),
                "After invalidation, a new configuration should be built");
    }

    @Test
    void should_clear_all_configurations_when_invalidate_all(@TempDir Path tempDir) throws Exception {
        Path repo1 = tempDir.resolve("repo1");
        Path repo2 = tempDir.resolve("repo2");
        Files.createDirectories(repo1.resolve("src/main/java"));
        Files.createDirectories(repo2.resolve("src/main/java"));

        service.createParser(repo1);
        service.createParser(repo2);

        assertEquals(2, service.getCacheSize(), "Should have 2 configurations cached");

        service.invalidateAll();

        assertEquals(0, service.getCacheSize(), "Cache should be empty after invalidateAll");
    }

    @Test
    void should_still_create_parser_when_source_root_missing(@TempDir Path tempDir) {
        Path repoRoot = tempDir.resolve("incomplete-repo");

        assertDoesNotThrow(() -> {
            JavaParser parser = service.createParser(repoRoot);
            assertNotNull(parser, "Should still create a parser even without source root");
        });
    }

    @Test
    void should_create_parser_with_multiple_type_solvers_when_repo_is_multi_module(@TempDir Path tempDir)
            throws Exception {
        String pomContent = """
                <project>
                    <packaging>pom</packaging>
                    <modules>
                        <module>mod-a</module>
                        <module>mod-b</module>
                    </modules>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pomContent);
        Files.createDirectories(tempDir.resolve("mod-a/src/main/java/com/example/a"));
        Files.createDirectories(tempDir.resolve("mod-b/src/main/java/com/example/b"));

        Files.writeString(tempDir.resolve("mod-a/src/main/java/com/example/a/Foo.java"),
                "package com.example.a; public class Foo {}");
        Files.writeString(tempDir.resolve("mod-b/src/main/java/com/example/b/Bar.java"),
                "package com.example.b; import com.example.a.Foo; public class Bar { private Foo foo; }");

        JavaParser parser = service.createParser(tempDir);

        Path barFile = tempDir.resolve("mod-b/src/main/java/com/example/b/Bar.java");
        assertTrue(parser.parse(barFile).isSuccessful(), "Should parse file from multi-module repo");
    }

    @Test
    void should_parse_concurrently_without_errors_when_multiple_threads_analyze_same_repo() throws Exception {
        Path repoRoot = Paths.get("src", "test", "resources", "fixtures", "spring-basic")
                .toAbsolutePath()
                .normalize();
        List<Path> javaFiles = List.of(
                repoRoot.resolve("src/main/java/com/example/basic/BasicController.java"),
                repoRoot.resolve("src/main/java/com/example/basic/BasicService.java"),
                repoRoot.resolve("src/main/java/com/example/basic/BasicServiceImpl.java"),
                repoRoot.resolve("src/main/java/com/example/basic/BasicRepository.java"));
        int threadCount = 8;
        int iterationsPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    int parsedCount = 0;
                    for (int iteration = 0; iteration < iterationsPerThread; iteration++) {
                        JavaParser parser = service.createParser(repoRoot);
                        for (Path javaFile : javaFiles) {
                            ParseResult<CompilationUnit> result = parser.parse(javaFile);
                            assertTrue(result.isSuccessful(),
                                    () -> "Concurrent parse failed: " + result.getProblems());
                            assertTrue(result.getResult().isPresent(),
                                    "Parse result should contain a CompilationUnit");
                            parsedCount++;
                        }
                    }
                    return parsedCount;
                }));
            }
            startGate.countDown();
            for (Future<Integer> future : futures) {
                assertEquals(iterationsPerThread * javaFiles.size(),
                        future.get(60, TimeUnit.SECONDS).intValue(),
                        "Every thread should parse all files without corruption");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
