package com.java.system.agent.analysis.entrypoint;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.java.system.agent.analysis.model.EntryPointClass;
import com.java.system.agent.analysis.model.EntryPointMethod;
import com.java.system.agent.analysis.model.EntryPointType;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Entry Point 掃描結果快取服務，協調 Parser 與 Extractor 提供統一掃描入口 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EntryPointCacheService {

    private final ProjectParserService projectParserService;
    private final SourceRootResolver sourceRootResolver;

    // Key: repoRootPath, Value: 所有類型的 entry points（immutable snapshot）
    private final ConcurrentHashMap<String, List<EntryPointClass>> cache = new ConcurrentHashMap<>();

    /** 取得 Entry Points，優先從快取取得 */
    public List<EntryPointClass> getEntryPoints(Path repoRootPath, List<EntryPointType> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }

        List<EntryPointClass> all = cache.computeIfAbsent(
                repoRootPath.toString(), k -> performFullScan(repoRootPath));

        return filterByTypes(all, filters);
    }

    /** 清除指定 Repo 快取並立即重新掃描 */
    public void reload(Path repoRootPath) {
        log.info("Refreshing cache for repository: {}", repoRootPath);
        cache.compute(repoRootPath.toString(), (k, old) -> performFullScan(repoRootPath));
    }

    /** 清除所有快取（不重建） */
    public void invalidateAll() {
        cache.clear();
        log.info("Cleared all entry point cache");
    }

    // --- Filter ---

    /** 從完整結果中篩選指定類型，回傳新 list（不修改 cache 內容） */
    private List<EntryPointClass> filterByTypes(List<EntryPointClass> all, List<EntryPointType> filters) {
        if (filters.containsAll(EntryPointType.ALL)) {
            return all;
        }

        List<EntryPointClass> result = new ArrayList<>();
        for (EntryPointClass epClass : all) {
            List<EntryPointMethod> matched = epClass.methods().stream()
                    .filter(m -> filters.contains(m.type()))
                    .toList();

            if (!matched.isEmpty()) {
                result.add(EntryPointClass.builder()
                        .className(epClass.className())
                        .packagePath(epClass.packagePath())
                        .basePath(epClass.basePath())
                        .description(epClass.description())
                        .methods(matched)
                        .build());
            }
        }
        return result;
    }

    // --- Scan ---

    /** 執行完整掃描（所有類型） */
    private List<EntryPointClass> performFullScan(Path repoRootPath) {
        JavaParser parser = projectParserService.getOrCreateParser(repoRootPath);

        List<Path> sourceRoots = sourceRootResolver.resolveSourceRoots(repoRootPath);
        if (CollectionUtils.isEmpty(sourceRoots)) {
            log.warn("No Java source roots found for: {}", repoRootPath);
            return List.of();
        }

        List<EntryPointClass> results = new ArrayList<>();

        for (Path javaSourceRoot : sourceRoots) {
            try (Stream<Path> paths = Files.walk(javaSourceRoot, 30)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .forEach(javaFilePath -> {
                            try {
                                processJavaFile(parser, javaFilePath, javaSourceRoot, results);
                            } catch (Exception e) {
                                log.error("Failed to process file {}: {}", javaFilePath, e.getMessage(), e);
                            }
                        });
            } catch (IOException e) {
                log.error("Failed to scan entry points for {}: {}", javaSourceRoot, e.getMessage(), e);
            }
        }

        log.debug("Scanned {} entry point classes for {}", results.size(), repoRootPath);
        return List.copyOf(results);
    }

    /** 處理單個 Java 檔案 */
    private void processJavaFile(JavaParser parser, Path javaFilePath, Path javaSourceRoot,
            List<EntryPointClass> results) {
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(javaFilePath);

            if (!parseResult.isSuccessful()) {
                log.warn("Failed to parse: {}", javaFilePath);
                return;
            }

            parseResult.getResult().ifPresent(cu -> processCompilationUnit(cu, javaFilePath, javaSourceRoot, results));
        } catch (IOException e) {
            log.error("IOException while parsing {}: {}", javaFilePath, e.getMessage());
        }
    }

    /** 處理編譯單元 */
    private void processCompilationUnit(CompilationUnit cu, Path javaFilePath, Path javaSourceRoot,
            List<EntryPointClass> results) {
        if (javaFilePath.toString().contains("/src/test/")) {
            return;
        }

        boolean isAdvice = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .anyMatch(c -> c.isAnnotationPresent("RestControllerAdvice") ||
                        c.isAnnotationPresent("ControllerAdvice"));
        if (isAdvice) {
            return;
        }

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = clazz.getNameAsString();

            if (ScannerUtils.isDeprecated(clazz)) {
                return;
            }

            String packagePath = javaSourceRoot.relativize(javaFilePath).toString();

            // 掃描所有類型，統一存入同一個 EntryPointClass
            List<EntryPointMethod> allMethods = new ArrayList<>();
            String basePath = null;

            ApiExtractionResult apiResult = ApiExtractor.extract(clazz);
            if (!apiResult.getEntryPoints().isEmpty()) {
                basePath = apiResult.getBasePath();
                allMethods.addAll(apiResult.getEntryPoints());
            }

            allMethods.addAll(MqExtractor.extract(clazz));
            allMethods.addAll(ScheduleExtractor.extract(clazz));

            if (!allMethods.isEmpty()) {
                results.add(EntryPointClass.builder()
                        .className(className)
                        .packagePath(packagePath)
                        .basePath(basePath)
                        .description(ScannerUtils.getClassJavadoc(clazz))
                        .methods(allMethods)
                        .build());
            }
        });
    }
}
