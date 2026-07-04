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

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClassMetadataService 測試
 * 使用 repos/test 的真實程式碼作為 fixture
 */
class ClassMetadataServiceTest {

    private static final Path REPO_ROOT = Paths.get("repos/test");
    private static final Path SRC_ROOT = REPO_ROOT.resolve("src/main/java");

    private ClassMetadataService classMetadataService;
    private JavaParser parser;

    @BeforeEach
    void setUp() {
        ProjectParserService projectParserService = new ProjectParserService(new SourceRootResolver());
        classMetadataService = new ClassMetadataService(new MapperXmlSqlExtractor(new SourceRootResolver()), projectParserService, new SourceRootResolver());
        classMetadataService.ensureInitialized(REPO_ROOT);
        parser = new JavaParser(new ParserConfiguration());
    }

    @Test
    void findBySimpleName_samePackage() throws Exception {
        MethodDeclaration contextMethod = parseMethodFromResource("com.example.service", "MainService");

        Optional<ClassMetadata> result = classMetadataService.findClassMetadataByName(
                "HelperService", contextMethod, REPO_ROOT
        );

        assertTrue(result.isPresent());
        assertEquals("HelperService", result.get().className());
        assertEquals("com.example.service", result.get().packageName());
    }

    @Test
    void findByQualifiedName() throws Exception {
        MethodDeclaration contextMethod = parseMethodFromResource("com.example.service", "MainService");

        Optional<ClassMetadata> result = classMetadataService.findClassMetadataByName(
                "com.example.contract.MyImpl", contextMethod, REPO_ROOT
        );

        assertTrue(result.isPresent());
        assertEquals("MyImpl", result.get().className());
        assertEquals("com.example.contract", result.get().packageName());
    }

    @Test
    void findBySimpleName_withImport() throws Exception {
        MethodDeclaration contextMethod = parseMethodFromResource("com.example.service", "MainService");

        Optional<ClassMetadata> result = classMetadataService.findClassMetadataByName(
                "MyInterface", contextMethod, REPO_ROOT
        );

        assertTrue(result.isPresent());
        assertEquals("MyInterface", result.get().className());
        assertEquals("com.example.contract", result.get().packageName());
    }

    @Test
    void resolveToAST() {
        Path helperFile = SRC_ROOT.resolve("com/example/service/HelperService.java");
        ClassMetadata metadata = ClassMetadata.builder()
                .className("HelperService")
                .packageName("com.example.service")
                .filePath(helperFile)
                .build();

        Optional<ClassOrInterfaceDeclaration> ast = classMetadataService.resolveToAST(metadata, REPO_ROOT);

        assertTrue(ast.isPresent());
        assertEquals("HelperService", ast.get().getNameAsString());
        assertTrue(ast.get().getMethods().stream().anyMatch(m -> m.getNameAsString().equals("doSomething")));
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

    // =========================================================================
    // Helper
    // =========================================================================

    private MethodDeclaration parseMethodFromResource(String packageName, String className)
            throws IOException {
        Path file = SRC_ROOT.resolve(packageName.replace('.', '/') + "/" + className + ".java");
        String source = Files.readString(file);
        CompilationUnit cu = parser.parse(source).getResult()
                .orElseThrow(() -> new RuntimeException("Failed to parse " + file));
        return cu.findAll(MethodDeclaration.class).get(0);
    }
}
