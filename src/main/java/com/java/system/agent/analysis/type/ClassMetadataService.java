package com.java.system.agent.analysis.type;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.java.system.agent.analysis.model.ClassMetadata;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;

import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.stream.Stream;

/**
 * Metadata cache and lookup — builds lightweight ClassMetadata from source files
 * and provides query methods (by name, package, interface, etc.).
 */
@Slf4j
@Component
public class ClassMetadataService {

    private record MetadataSnapshot(
        List<ClassMetadata> all,
        Map<String, List<ClassMetadata>> byName
    ) {
        static MetadataSnapshot of(List<ClassMetadata> metadata) {
            List<ClassMetadata> all = List.copyOf(metadata);
            Map<String, List<ClassMetadata>> byName = new HashMap<>();
            for (ClassMetadata m : all) {
                byName.computeIfAbsent(m.className(), k -> new ArrayList<>()).add(m);
            }
            return new MetadataSnapshot(all, Collections.unmodifiableMap(byName));
        }

        static final MetadataSnapshot EMPTY = new MetadataSnapshot(List.of(), Map.of());
    }

    private final ConcurrentHashMap<String, MetadataSnapshot> cache = new ConcurrentHashMap<>();
    private final MapperXmlSqlExtractor mapperXmlSqlExtractor;
    private final ProjectParserService projectParserService;
    private final SourceRootResolver sourceRootResolver;

    public ClassMetadataService(MapperXmlSqlExtractor mapperXmlSqlExtractor,
            ProjectParserService projectParserService,
            SourceRootResolver sourceRootResolver) {
        this.mapperXmlSqlExtractor = mapperXmlSqlExtractor;
        this.projectParserService = projectParserService;
        this.sourceRootResolver = sourceRootResolver;
    }

    private JavaParser getParser(Path repoRoot) {
        return projectParserService.getOrCreateParser(repoRoot);
    }

    public void reload(Path repoRoot) {
        cache.compute(repoRoot.toString(), (k, old) -> {
            mapperXmlSqlExtractor.invalidate(repoRoot);
            return buildSnapshot(repoRoot);
        });
    }

    private Optional<CompilationUnit> parseFile(Path path, Path repoRoot) {
        try {
            return getParser(repoRoot).parse(path).getResult();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // --- Metadata Cache ---

    private MetadataSnapshot buildSnapshot(Path repoRoot) {
        List<Path> sourceRoots = sourceRootResolver.resolveSourceRoots(repoRoot);
        if (CollectionUtils.isEmpty(sourceRoots)) {
            log.warn("No Java source roots found for: {}", repoRoot);
            return MetadataSnapshot.EMPTY;
        }

        List<ClassMetadata> metadata = new ArrayList<>();
        for (Path javaSourceRoot : sourceRoots) {
            try (Stream<Path> walk = Files.walk(javaSourceRoot, 30)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> {
                            Optional<CompilationUnit> cu = parseFile(p, repoRoot);
                            if (cu.isPresent()) {
                                cu.get().findAll(ClassOrInterfaceDeclaration.class)
                                        .forEach(cls -> metadata.add(extractMetadata(cls, p, repoRoot)));
                            }
                        });
            } catch (IOException e) {
                log.error("Failed to build metadata cache for source root: {}", javaSourceRoot, e);
            }
        }
        log.debug("Built metadata cache for {} with {} classes from {} source roots",
                repoRoot, metadata.size(), sourceRoots.size());
        return MetadataSnapshot.of(metadata);
    }

    private ClassMetadata extractMetadata(ClassOrInterfaceDeclaration cls, Path filePath, Path repoRoot) {
        String packageName = cls.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString())
                .orElse("");

        String fullyQualifiedName = packageName.isEmpty()
                ? cls.getNameAsString()
                : packageName + "." + cls.getNameAsString();

        List<String> annotations = cls.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .toList();

        List<String> implementedTypes = cls.getImplementedTypes().stream()
                .map(t -> t.getNameAsString())
                .toList();

        List<String> extendedTypes = cls.getExtendedTypes().stream()
                .map(t -> t.getNameAsString())
                .toList();

        List<String> imports = cls.findCompilationUnit()
                .map(cu -> cu.getImports().stream()
                        .map(imp -> imp.getNameAsString())
                        .toList())
                .orElse(Collections.emptyList());

        return ClassMetadata.builder()
                .className(cls.getNameAsString())
                .packageName(packageName)
                .filePath(filePath)
                .isInterface(cls.isInterface())
                .isAbstract(cls.isAbstract())
                .implementedTypes(implementedTypes)
                .extendedTypes(extendedTypes)
                .methods(extractMethods(cls, fullyQualifiedName, repoRoot))
                .annotations(annotations)
                .fields(extractFields(cls))
                .imports(imports)
                .hasFluentAccessors(detectFluentAccessors(cls))
                .profiles(extractProfiles(cls))
                .build();
    }

    private List<ClassMetadata.MethodSignature> extractMethods(ClassOrInterfaceDeclaration cls,
            String fullyQualifiedName, Path repoRoot) {
        return cls.getMethods().stream()
                .map(m -> {
                    String sql = extractSqlFromAnnotations(m);
                    if (sql == null) {
                        sql = mapperXmlSqlExtractor.findSql(fullyQualifiedName, m.getNameAsString(), repoRoot)
                                .orElse(null);
                    }

                    return ClassMetadata.MethodSignature.builder()
                            .name(m.getNameAsString())
                            .paramCount(m.getParameters().size())
                            .paramTypes(m.getParameters().stream()
                                    .map(p -> ScopeTypeResolver.simpleTypeName(p.getType().asString()))
                                    .toList())
                            .annotations(m.getAnnotations().stream()
                                    .map(a -> a.getNameAsString())
                                    .toList())
                            .sql(sql)
                            .build();
                })
                .toList();
    }

    private List<String> extractProfiles(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("Profile") || a.getNameAsString().endsWith(".Profile"))
                .flatMap(a -> {
                    if (a.isSingleMemberAnnotationExpr()) {
                        return Stream.of(a.asSingleMemberAnnotationExpr().getMemberValue().toString()
                                .replaceAll("[\"{}]", "").split(","));
                    } else if (a.isNormalAnnotationExpr()) {
                        return a.asNormalAnnotationExpr().getPairs().stream()
                                .filter(p -> p.getNameAsString().equals("value"))
                                .flatMap(p -> Stream.of(p.getValue().toString().replaceAll("[\"{}]", "").split(",")));
                    }
                    return Stream.empty();
                })
                .map(String::trim)
                .toList();
    }

    private List<ClassMetadata.FieldInfo> extractFields(ClassOrInterfaceDeclaration cls) {
        return cls.getFields().stream()
                .flatMap(f -> f.getVariables().stream())
                .map(v -> ClassMetadata.FieldInfo.builder()
                        .name(v.getNameAsString())
                        .type(ScopeTypeResolver.simpleTypeName(v.getType().asString()))
                        .build())
                .toList();
    }

    private boolean detectFluentAccessors(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
                .filter(ann -> {
                    String name = ann.getNameAsString();
                    return name.equals("Accessors") || name.endsWith(".Accessors");
                })
                .anyMatch(ann -> ann.isNormalAnnotationExpr()
                        && ann.asNormalAnnotationExpr().getPairs().stream()
                                .anyMatch(pair -> pair.getNameAsString().equals("fluent")
                                        && pair.getValue().toString().equals("true")));
    }

    private String extractSqlFromAnnotations(MethodDeclaration m) {
        Set<String> mybatisAnns = Set.of("Select", "Update", "Insert", "Delete");
        return m.getAnnotations().stream()
                .filter(a -> mybatisAnns.contains(a.getNameAsString())
                        || mybatisAnns.stream().anyMatch(n -> a.getNameAsString().endsWith("." + n)))
                .map(a -> {
                    if (a.isSingleMemberAnnotationExpr()) {
                        return a.asSingleMemberAnnotationExpr().getMemberValue().toString().replaceAll("^\"|\"$", "");
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // --- Cache Lifecycle（由 Analyzer / AnalysisService 觸發，query method 不再自行 init）---

    /**
     * 確保 metadata cache 已建立（冪等）
     * 觸發點：JavaCallGraphAnalyzer.analyze() 在進入 builder 前呼叫一次
     */
    public void ensureInitialized(Path repoRoot) {
        cache.computeIfAbsent(repoRoot.toString(), k -> buildSnapshot(repoRoot));
    }

    // --- Query Methods（純讀，假設 cache 已 warm）---

    private MetadataSnapshot getSnapshot(Path repoRoot) {
        return cache.getOrDefault(repoRoot.toString(), MetadataSnapshot.EMPTY);
    }

    private List<ClassMetadata> getClassMetadata(Path repoRoot) {
        return getSnapshot(repoRoot).all();
    }

    public Optional<ClassMetadata> findClassMetadata(Path repoRoot,
            String className, String packageName) {
        return getClassMetadata(repoRoot).stream()
                .filter(m -> m.className().equals(className))
                .filter(m -> m.packageName().equals(packageName))
                .findFirst();
    }

    /**
     * 從 metadata 的 filePath 重新 parse 取得 AST 節點
     * <p>
     * 注意：每次呼叫都會重新 parse 檔案（不快取 AST），
     * 在 call graph 遞迴中同一個檔案可能被 parse 多次
     * 未來可考慮加入 LRU cache，但需先評估記憶體佔用
     */
    public Optional<ClassOrInterfaceDeclaration> resolveToAST(ClassMetadata metadata, Path repoRoot) {
        return parseFile(metadata.filePath(), repoRoot)
                .flatMap(cu -> cu.findFirst(ClassOrInterfaceDeclaration.class,
                        c -> c.getNameAsString().equals(metadata.className())));
    }

    public Optional<ClassMetadata> findClassMetadataByName(String typeName, MethodDeclaration currentMethod,
            Path repoRoot) {
        String cleanedName = ScopeTypeResolver.stripGenerics(typeName);

        Optional<CompilationUnit> cuOpt = currentMethod.findCompilationUnit();
        String currentPkg = cuOpt.flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString()).orElse("");

        List<String> imports = cuOpt
                .map(cu -> cu.getImports().stream()
                        .map(imp -> imp.getNameAsString())
                        .toList())
                .orElse(List.of());

        return findByCleanedName(cleanedName, currentPkg, imports, repoRoot);
    }

    /**
     * Metadata-based type lookup — 不依賴 AST，用 caller 的 metadata 取得 package + imports
     */
    public Optional<ClassMetadata> findClassMetadataByName(String typeName, ClassMetadata callerMetadata,
            Path repoRoot) {
        String cleanedName = ScopeTypeResolver.stripGenerics(typeName);
        return findByCleanedName(cleanedName, callerMetadata.packageName(), callerMetadata.imports(), repoRoot);
    }

    // --- Metadata Lookup Helpers ---

    /**
     * 共用搜尋邏輯：qualified → same package → imports → any simple name
     */
    private Optional<ClassMetadata> findByCleanedName(String cleanedName, String currentPkg,
            List<String> imports, Path repoRoot) {
        if (cleanedName.contains(".")) {
            return findMetadataByQualifiedName(cleanedName, repoRoot);
        }

        Optional<ClassMetadata> samePkgResult = findMetadataInPackage(currentPkg, cleanedName, repoRoot);
        if (samePkgResult.isPresent()) return samePkgResult;

        if (imports != null && !imports.isEmpty()) {
            Optional<ClassMetadata> importResult = resolveFromImports(imports, cleanedName, repoRoot);
            if (importResult.isPresent()) return importResult;
        }

        return findAnyMetadataBySimpleName(cleanedName, repoRoot);
    }

    private Optional<ClassMetadata> findMetadataByQualifiedName(String qualifiedName, Path repoRoot) {
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot == -1)
            return Optional.empty();
        String pkg = qualifiedName.substring(0, lastDot);
        String simpleName = qualifiedName.substring(lastDot + 1);
        return findMetadataInPackage(pkg, simpleName, repoRoot);
    }

    private Optional<ClassMetadata> findMetadataInPackage(String pkg, String simpleName, Path repoRoot) {
        return getClassMetadata(repoRoot).stream()
                .filter(m -> m.className().equals(simpleName) && m.packageName().equals(pkg))
                .findFirst();
    }

    /**
     * Import-based lookup（metadata 版）— 不區分 explicit / asterisk，兩種都嘗試
     * explicit import "com.example.Foo" → endsWith(".Foo") 命中 → qualified lookup
     * asterisk import "com.example" → endsWith 不命中 → 當作 package 做 findMetadataInPackage
     */
    private Optional<ClassMetadata> resolveFromImports(List<String> imports, String simpleName, Path repoRoot) {
        for (String impName : imports) {
            if (impName.endsWith("." + simpleName)) {
                return findMetadataByQualifiedName(impName, repoRoot);
            }
            Optional<ClassMetadata> res = findMetadataInPackage(impName, simpleName, repoRoot);
            if (res.isPresent()) return res;
        }
        return Optional.empty();
    }

    private Optional<ClassMetadata> findAnyMetadataBySimpleName(String simpleName, Path repoRoot) {
        Map<String, List<ClassMetadata>> index = getSnapshot(repoRoot).byName();
        List<ClassMetadata> matches = index.get(simpleName);
        if (matches == null || matches.isEmpty()) return Optional.empty();
        return Optional.of(matches.get(0));
    }

    public List<ClassMetadata> findImplementingClasses(Path repoRoot, String interfaceName) {
        return getClassMetadata(repoRoot).stream()
                .filter(m -> m.implementedTypes().contains(interfaceName))
                .toList();
    }

    public Optional<String> findMapperXmlSql(ClassMetadata metadata, String methodId, Path repoRoot) {
        String fqn = StringUtils.hasText(metadata.packageName())
                ? metadata.packageName() + "." + metadata.className()
                : metadata.className();
        return mapperXmlSqlExtractor.findSql(fqn, methodId, repoRoot);
    }
}
