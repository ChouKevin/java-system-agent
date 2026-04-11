package com.java.system.agent.analysis.parser;

import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 測試 ProjectParserService
 */
class ProjectParserServiceTest {

    private ProjectParserService service;

    @BeforeEach
    void setUp() {
        service = new ProjectParserService(new SourceRootResolver());
    }

    @Test
    void testGetOrCreateParser_SameRepoReturnsSameInstance(@TempDir Path tempDir) throws Exception {
        // Arrange: 創建一個臨時 repo 目錄結構
        Path repoRoot = tempDir.resolve("test-repo");
        Path srcMainJava = repoRoot.resolve("src/main/java");
        Files.createDirectories(srcMainJava);

        // Act: 兩次獲取同一個 repo 的 Parser
        JavaParser parser1 = service.getOrCreateParser(repoRoot);
        JavaParser parser2 = service.getOrCreateParser(repoRoot);

        // Assert: 應該返回同一個實例（快取）
        assertSame(parser1, parser2, "Same repo should return cached parser instance");
    }

    @Test
    void testGetOrCreateParser_DifferentReposReturnDifferentInstances(@TempDir Path tempDir) throws Exception {
        // Arrange: 創建兩個不同的 repo
        Path repo1 = tempDir.resolve("repo1");
        Path repo2 = tempDir.resolve("repo2");
        Files.createDirectories(repo1.resolve("src/main/java"));
        Files.createDirectories(repo2.resolve("src/main/java"));

        // Act: 獲取兩個不同 repo 的 Parser
        JavaParser parser1 = service.getOrCreateParser(repo1);
        JavaParser parser2 = service.getOrCreateParser(repo2);

        // Assert: 應該返回不同的實例
        assertNotSame(parser1, parser2, "Different repos should have different parser instances");
    }

    @Test
    void testInvalidate_RemovesParserFromCache(@TempDir Path tempDir) throws Exception {
        // Arrange
        Path repoRoot = tempDir.resolve("test-repo");
        Files.createDirectories(repoRoot.resolve("src/main/java"));

        JavaParser parser1 = service.getOrCreateParser(repoRoot);

        // Act: 清除快取
        service.invalidate(repoRoot);
        JavaParser parser2 = service.getOrCreateParser(repoRoot);

        // Assert: 清除後應該創建新實例
        assertNotSame(parser1, parser2, "After invalidation, should create new parser instance");
    }

    @Test
    void testInvalidateAll_ClearsAllParsers(@TempDir Path tempDir) throws Exception {
        // Arrange: 創建並快取多個 Parser
        Path repo1 = tempDir.resolve("repo1");
        Path repo2 = tempDir.resolve("repo2");
        Files.createDirectories(repo1.resolve("src/main/java"));
        Files.createDirectories(repo2.resolve("src/main/java"));

        service.getOrCreateParser(repo1);
        service.getOrCreateParser(repo2);

        assertEquals(2, service.getCacheSize(), "Should have 2 parsers cached");

        // Act: 清除所有快取
        service.invalidateAll();

        // Assert
        assertEquals(0, service.getCacheSize(), "Cache should be empty after invalidateAll");
    }

    @Test
    void testGetOrCreateParser_HandlesNonExistentSourceRoot(@TempDir Path tempDir) {
        // Arrange: 創建 repo 但不創建 src/main/java 目錄
        Path repoRoot = tempDir.resolve("incomplete-repo");

        // Act & Assert: 應該仍然能創建 Parser（只是會警告）
        assertDoesNotThrow(() -> {
            JavaParser parser = service.getOrCreateParser(repoRoot);
            assertNotNull(parser, "Should still create a parser even without source root");
        });
    }

    @Test
    void should_create_parser_with_multiple_type_solvers_for_multi_module(@TempDir Path tempDir) throws Exception {
        // Arrange: multi-module repo
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

        // Create a Java file in mod-a
        Files.writeString(tempDir.resolve("mod-a/src/main/java/com/example/a/Foo.java"),
                "package com.example.a; public class Foo {}");
        // Create a Java file in mod-b that references mod-a's class
        Files.writeString(tempDir.resolve("mod-b/src/main/java/com/example/b/Bar.java"),
                "package com.example.b; import com.example.a.Foo; public class Bar { private Foo foo; }");

        // Act: create parser
        JavaParser parser = service.getOrCreateParser(tempDir);

        // Assert: parser should be able to parse mod-b's file (symbol resolution across modules)
        Path barFile = tempDir.resolve("mod-b/src/main/java/com/example/b/Bar.java");
        assertTrue(parser.parse(barFile).isSuccessful(), "Should parse file from multi-module repo");
    }
}
