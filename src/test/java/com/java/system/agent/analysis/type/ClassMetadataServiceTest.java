package com.java.system.agent.analysis.type;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.java.system.agent.analysis.model.ClassMetadata;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassMetadataServiceTest {

    private static final Path REPO_ROOT = Paths.get("src/test/resources/fixtures/multi-module-data-access");
    private static final Path SERVICE_SRC_ROOT = REPO_ROOT.resolve("module-service/src/main/java");

    private ClassMetadataService classMetadataService;
    private JavaParser parser;

    @BeforeEach
    void setUp() {
        SourceRootResolver sourceRootResolver = new SourceRootResolver();
        ProjectParserService projectParserService = new ProjectParserService(sourceRootResolver);
        classMetadataService = new ClassMetadataService(
                new MapperXmlSqlExtractor(sourceRootResolver),
                projectParserService,
                sourceRootResolver);
        classMetadataService.ensureInitialized(REPO_ROOT);
        parser = new JavaParser(new ParserConfiguration());
    }

    @Test
    void findBySimpleName_samePackage() throws Exception {
        MethodDeclaration contextMethod = parseMethodFromServiceResource(
                "com.example.service", "OrderApplicationService");

        Optional<ClassMetadata> result = classMetadataService.findClassMetadataByName(
                "OrderServiceHelper", contextMethod, REPO_ROOT);

        assertTrue(result.isPresent());
        assertEquals("OrderServiceHelper", result.get().className());
        assertEquals("com.example.service", result.get().packageName());
    }

    @Test
    void findByQualifiedName() throws Exception {
        MethodDeclaration contextMethod = parseMethodFromServiceResource(
                "com.example.service", "OrderApplicationService");

        Optional<ClassMetadata> result = classMetadataService.findClassMetadataByName(
                "com.example.persistence.JdbcOrderRepository", contextMethod, REPO_ROOT);

        assertTrue(result.isPresent());
        assertEquals("JdbcOrderRepository", result.get().className());
        assertEquals("com.example.persistence", result.get().packageName());
    }

    @Test
    void findBySimpleName_withImport() throws Exception {
        MethodDeclaration contextMethod = parseMethodFromServiceResource(
                "com.example.service", "OrderApplicationService");

        Optional<ClassMetadata> result = classMetadataService.findClassMetadataByName(
                "OrderMapper", contextMethod, REPO_ROOT);

        assertTrue(result.isPresent());
        assertEquals("OrderMapper", result.get().className());
        assertEquals("com.example.persistence", result.get().packageName());
    }

    @Test
    void resolveToAST() {
        Path helperFile = SERVICE_SRC_ROOT.resolve("com/example/service/OrderServiceHelper.java");
        ClassMetadata metadata = ClassMetadata.builder()
                .className("OrderServiceHelper")
                .packageName("com.example.service")
                .filePath(helperFile)
                .build();

        Optional<ClassOrInterfaceDeclaration> ast = classMetadataService.resolveToAST(metadata, REPO_ROOT);

        assertTrue(ast.isPresent());
        assertEquals("OrderServiceHelper", ast.get().getNameAsString());
        assertTrue(ast.get().getMethods().stream()
                .anyMatch(method -> method.getNameAsString().equals("normalize")));
    }

    @Test
    void shouldExtractMethodSourceSpan(@TempDir Path repoRoot) throws IOException {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/basic/BasicService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example.basic;

                class BasicService {
                    String getBasic(String id) {
                        return id.trim();
                    }
                }
                """);

        classMetadataService.ensureInitialized(repoRoot);

        ClassMetadata metadata = classMetadataService.findClassMetadata(
                        repoRoot, "BasicService", "com.example.basic")
                .orElseThrow();
        ClassMetadata.MethodSignature method = metadata.methods().stream()
                .filter(candidate -> candidate.name().equals("getBasic"))
                .findFirst()
                .orElseThrow();
        assertEquals(4, method.startLine());
        assertEquals(6, method.endLine());
    }

    @Test
    void shouldAllowLegacyMethodSignatureConstructorWithoutSourceSpan() {
        ClassMetadata.MethodSignature method = new ClassMetadata.MethodSignature(
                "getBasic",
                1,
                List.of("String"),
                List.of(),
                null);

        assertNull(method.startLine());
        assertNull(method.endLine());
    }

    private MethodDeclaration parseMethodFromServiceResource(String packageName, String className)
            throws IOException {
        Path file = SERVICE_SRC_ROOT.resolve(packageName.replace('.', '/') + "/" + className + ".java");
        String source = Files.readString(file);
        CompilationUnit cu = parser.parse(source).getResult()
                .orElseThrow(() -> new RuntimeException("Failed to parse " + file));
        return cu.findAll(MethodDeclaration.class).get(0);
    }
}
