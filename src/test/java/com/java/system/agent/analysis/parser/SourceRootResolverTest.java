package com.java.system.agent.analysis.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceRootResolverTest {

    private SourceRootResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SourceRootResolver();
    }

    @Test
    void should_return_single_source_root_when_no_pom_xml(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot);

        List<Path> roots = resolver.resolveSourceRoots(tempDir);

        assertEquals(1, roots.size());
        assertEquals(sourceRoot, roots.get(0));
    }

    @Test
    void should_return_multiple_source_roots_when_multi_module(@TempDir Path tempDir) throws Exception {
        String pomContent = """
                <project>
                    <packaging>pom</packaging>
                    <modules>
                        <module>mod-core</module>
                        <module>mod-service</module>
                        <module>mod-api</module>
                    </modules>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pomContent);
        Files.createDirectories(tempDir.resolve("mod-core/src/main/java"));
        Files.createDirectories(tempDir.resolve("mod-service/src/main/java"));

        List<Path> roots = resolver.resolveSourceRoots(tempDir);

        assertEquals(2, roots.size());
        assertTrue(roots.contains(tempDir.resolve("mod-core/src/main/java")));
        assertTrue(roots.contains(tempDir.resolve("mod-service/src/main/java")));
    }

    @Test
    void should_return_cached_result_on_second_call(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));

        List<Path> first = resolver.resolveSourceRoots(tempDir);
        List<Path> second = resolver.resolveSourceRoots(tempDir);

        assertSame(first, second, "Second call should return cached list");
    }

    @Test
    void should_refresh_after_invalidate(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));

        List<Path> first = resolver.resolveSourceRoots(tempDir);
        resolver.invalidate(tempDir);
        List<Path> second = resolver.resolveSourceRoots(tempDir);

        assertNotSame(first, second, "After invalidation, should recompute");
        assertEquals(first, second);
    }

    @Test
    void should_resolve_resource_roots_for_multi_module(@TempDir Path tempDir) throws Exception {
        String pomContent = """
                <project>
                    <packaging>pom</packaging>
                    <modules>
                        <module>mod-core</module>
                        <module>mod-service</module>
                    </modules>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pomContent);
        Files.createDirectories(tempDir.resolve("mod-core/src/main/resources"));
        Files.createDirectories(tempDir.resolve("mod-service/src/main/resources"));

        List<Path> roots = resolver.resolveResourceRoots(tempDir);

        assertEquals(2, roots.size());
    }

    @Test
    void should_return_empty_when_no_source_root_exists(@TempDir Path tempDir) {
        List<Path> roots = resolver.resolveSourceRoots(tempDir);
        assertTrue(roots.isEmpty());
    }

    @Test
    void should_resolve_go_track_service_modules() {
        Path goTrackRepo = Path.of("repos/go-track-service");
        if (!Files.exists(goTrackRepo.resolve("pom.xml"))) {
            return; // skip if repo not cloned
        }

        List<Path> roots = resolver.resolveSourceRoots(goTrackRepo);

        assertEquals(6, roots.size(), "go-track-service has 6 modules with src/main/java");
    }
}
