package com.java.system.agent.analysis.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.analysis.entrypoint.ApiEntryPoint;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.fixture.FixtureRepoLoader;
import com.java.system.agent.analysis.model.ApiRouteCandidate;
import com.java.system.agent.analysis.model.ClassMetadata;
import com.java.system.agent.analysis.model.EntryPointClass;
import com.java.system.agent.analysis.model.EntryPointType;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.port.SourceCodePort;
import com.java.system.agent.analysis.trie.ApiEntryPointRef;
import com.java.system.agent.analysis.trie.ApiTrieService;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 框架萃取行為的 golden 測試
 *
 * 凍結語法層／框架層的萃取結果：API 路由、trie 比對、MyBatis SQL evidence
 * 這些行為只依賴語法解析，替換分析引擎後必須逐字重現
 *
 * 刻意不涵蓋呼叫目標的語意解析（繼承方法、泛型代換、多載選擇、fallback 的 CallType）
 * 那些是目前 pipeline 的已知限制，預期會隨引擎替換而改善
 * 凍結它們會讓後續遷移無法同時成功與通過測試
 *
 * 以 {@code -Dgolden.regenerate=true} 重新產生 golden，產生後必須人工檢視 diff 再提交
 */
class FrameworkExtractionGoldenTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/goldens");
    private static final boolean REGENERATE = Boolean.getBoolean("golden.regenerate");
    private static final String FIXTURE_PLACEHOLDER = "{fixture}";
    private static final String JAVA_SUFFIX = ".java";

    /** spring-basic 只有 GET /basic/{id} 一條路由，探針覆蓋路徑正規化、verb 篩選與未命中 */
    private static final List<TrieLookupProbe> SPRING_BASIC_PROBES = List.of(
            new TrieLookupProbe("GET", "/basic/42"),
            new TrieLookupProbe("GET", "/basic/{id}"),
            new TrieLookupProbe("GET", "/basic/42/"),
            new TrieLookupProbe("GET", "//basic//42"),
            new TrieLookupProbe("GET", "/basic"),
            new TrieLookupProbe("GET", "/basic/42/extra"),
            new TrieLookupProbe("POST", "/basic/42"),
            new TrieLookupProbe("", "/basic/42"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FixtureRepoLoader fixtureRepoLoader = new FixtureRepoLoader();

    @Test
    void should_match_golden_when_api_routes_extracted_from_spring_basic(@TempDir Path tempDir)
            throws Exception {
        Path fixtureRoot = copyFixture("spring-basic", tempDir);
        GoldenSnapshot actual = extractApiRoutes("spring-basic", fixtureRoot, SPRING_BASIC_PROBES);
        assertGolden("api-routes-spring-basic.json", actual, fixtureRoot);
    }

    @Test
    void should_match_golden_when_sql_evidence_extracted_from_multi_module(@TempDir Path tempDir)
            throws Exception {
        Path fixtureRoot = copyFixture("multi-module-data-access", tempDir);
        GoldenSnapshot actual = extractSqlEvidence("multi-module-data-access", fixtureRoot);
        assertGolden("sql-evidence-multi-module.json", actual, fixtureRoot);
    }

    // --- Fixture ---

    /**
     * 將 fixture 複製到形如真實 repo 的暫存根目錄
     *
     * fixture 原地位於 src/test/resources 之下，EntryPointCacheService 會把絕對路徑中含 /src/test/
     * 的檔案視為測試碼而整批略過，掃描結果將全空
     * 該排除規則對真實 repo 是正確的，因此改為搬移 fixture 而非放寬掃描器
     */
    private Path copyFixture(String fixture, Path tempDir) throws IOException {
        Path source = fixtureRepoLoader.fixtureRoot(fixture);
        Path target = tempDir.resolve(fixture);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }

    // --- Extraction ---

    /** 掃描 fixture 的 API entry point，並以探針查詢 trie 取得比對結果 */
    private ApiRoutesGolden extractApiRoutes(String fixture, Path fixtureRoot,
            List<TrieLookupProbe> probes) {
        SourceRootResolver sourceRootResolver = new SourceRootResolver();
        EntryPointCacheService entryPointCacheService = new EntryPointCacheService(
                new ProjectParserService(sourceRootResolver), sourceRootResolver);
        SourceCodePort sourceCodePort = repoId -> fixtureRoot;
        ApiTrieService apiTrieService = new ApiTrieService(sourceCodePort, entryPointCacheService);
        apiTrieService.reload(fixture);

        List<ApiClassSnapshot> classes = entryPointCacheService
                .getEntryPoints(fixtureRoot, List.of(EntryPointType.API)).stream()
                .map(ApiClassSnapshot::from)
                .toList();
        List<TrieLookupSnapshot> lookups = probes.stream()
                .map(probe -> probe.resolve(apiTrieService, fixture))
                .toList();
        return new ApiRoutesGolden(fixture, classes, lookups);
    }

    /** 蒐集 fixture 內所有帶 SQL 的 mapper 方法，同時記錄 metadata 解析值與 XML 查得值 */
    private SqlEvidenceGolden extractSqlEvidence(String fixture, Path fixtureRoot) {
        SourceRootResolver sourceRootResolver = new SourceRootResolver();
        ClassMetadataService classMetadataService = new ClassMetadataService(
                new MapperXmlSqlExtractor(sourceRootResolver),
                new ProjectParserService(sourceRootResolver),
                sourceRootResolver);
        classMetadataService.ensureInitialized(fixtureRoot);

        List<SqlEvidenceSnapshot> evidence = new ArrayList<>();
        for (ClassMetadata metadata : findAllClassMetadata(
                classMetadataService, sourceRootResolver, fixtureRoot)) {
            for (ClassMetadata.MethodSignature method : metadata.methods()) {
                String mapperXmlSql = classMetadataService
                        .findMapperXmlSql(metadata, method.name(), fixtureRoot)
                        .orElse(null);
                if (Objects.isNull(method.sql()) && Objects.isNull(mapperXmlSql)) {
                    continue;
                }
                evidence.add(new SqlEvidenceSnapshot(
                        metadata.packageName(),
                        metadata.className(),
                        metadata.filePath().toString(),
                        method.name(),
                        method.paramTypes(),
                        method.annotations(),
                        method.sql(),
                        mapperXmlSql));
            }
        }
        return new SqlEvidenceGolden(fixture, evidence);
    }

    /** 走訪 fixture 的所有 source root，取得各檔案 top-level 類別的 metadata */
    private List<ClassMetadata> findAllClassMetadata(ClassMetadataService classMetadataService,
            SourceRootResolver sourceRootResolver, Path fixtureRoot) {
        List<ClassMetadata> metadata = new ArrayList<>();
        for (Path sourceRoot : sourceRootResolver.resolveSourceRoots(fixtureRoot)) {
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(JAVA_SUFFIX))
                        .forEach(path -> classMetadataService.findClassMetadata(fixtureRoot,
                                        toClassName(path), toPackageName(sourceRoot, path))
                                .ifPresent(metadata::add));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to walk fixture source root: " + sourceRoot, e);
            }
        }
        return metadata;
    }

    // --- Golden ---

    /**
     * 比對正規化後的 golden
     *
     * 以 {@code -Dgolden.regenerate=true} 重新產生，產生後必須人工檢視 diff 再提交
     * 比對刻意不忽略空白，SQL 內的空白屬於要交給 LLM 的證據，收斂掉就驗不出迴歸
     */
    private void assertGolden(String name, GoldenSnapshot actual, Path fixtureRoot)
            throws IOException {
        assertExtractionNonEmpty(name, actual);

        String serialized = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(normalize(actual, fixtureRoot));
        Path golden = GOLDEN_DIR.resolve(name);

        if (REGENERATE) {
            Files.createDirectories(GOLDEN_DIR);
            Files.writeString(golden, serialized + System.lineSeparator());
            return;
        }
        assertThat(golden)
                .as("golden %s is missing; run with -Dgolden.regenerate=true and review the diff", name)
                .exists();
        assertThat(serialized.strip())
                .as("golden %s changed; regenerate with -Dgolden.regenerate=true and review the diff", name)
                .isEqualToNormalizingNewlines(Files.readString(golden).strip());
    }

    /**
     * 萃取結果不得為空，兩種模式都要檢查
     *
     * EntryPointCacheService 會略過絕對路徑含 /src/test/ 的檔案，若 fixture 未正確搬移到
     * 暫存根目錄，掃描結果會整批變空，但測試本身仍可能通過
     * regenerate 模式若略過此檢查，會把「掃描器略過 fixture」的空結果凍結成 golden
     * 此檢查必須放在 REGENERATE 分支之前，讓兩種模式都受保護
     */
    private void assertExtractionNonEmpty(String name, GoldenSnapshot actual) {
        switch (actual) {
            case ApiRoutesGolden golden -> Assert.notEmpty(golden.classes(),
                    "golden " + name + " extraction produced no API entry point classes; "
                            + "the fixture root is probably being skipped as test code "
                            + "(see EntryPointCacheService /src/test/ exclusion)");
            case SqlEvidenceGolden golden -> Assert.notEmpty(golden.evidence(),
                    "golden " + name + " extraction produced no SQL evidence; "
                            + "the fixture root is probably being skipped as test code "
                            + "(see EntryPointCacheService /src/test/ exclusion)");
        }
    }

    /**
     * 正規化 golden payload
     *
     * 絕對路徑換成 {@code {fixture}} placeholder、檔案系統走訪順序改為穩定排序、
     * javadoc 之類的文字證據收斂空白
     * SQL 內容與方法參數順序刻意保持原樣，前者的空白可能具語意，後者的順序即是簽名
     */
    private GoldenSnapshot normalize(GoldenSnapshot actual, Path fixtureRoot) {
        Assert.notNull(actual, "Golden payload must not be null");
        Assert.notNull(fixtureRoot, "Fixture root must not be null");
        return actual.normalized(fixtureRoot);
    }

    // --- Snapshots ---

    sealed interface GoldenSnapshot permits ApiRoutesGolden, SqlEvidenceGolden {
        /** 產生可穩定比對的正規化副本，fixtureRoot 用於抹除 checkout 位置 */
        GoldenSnapshot normalized(Path fixtureRoot);
    }

    record ApiRoutesGolden(String fixture, List<ApiClassSnapshot> classes,
            List<TrieLookupSnapshot> lookups) implements GoldenSnapshot {

        /** 本 snapshot 只帶 source root 相對路徑，不需要 fixtureRoot */
        @Override
        public ApiRoutesGolden normalized(Path fixtureRoot) {
            List<ApiClassSnapshot> sortedClasses = classes.stream()
                    .map(ApiClassSnapshot::normalized)
                    .sorted(Comparator.comparing(ApiClassSnapshot::packagePath)
                            .thenComparing(ApiClassSnapshot::className))
                    .toList();
            List<TrieLookupSnapshot> sortedLookups = lookups.stream()
                    .sorted(Comparator.comparing(TrieLookupSnapshot::request))
                    .toList();
            return new ApiRoutesGolden(fixture, sortedClasses, sortedLookups);
        }
    }

    record ApiClassSnapshot(String className, String packagePath, String basePath,
            String description, List<ApiMethodSnapshot> apis) {

        static ApiClassSnapshot from(EntryPointClass entryPointClass) {
            List<ApiMethodSnapshot> apis = entryPointClass.methods().stream()
                    .filter(ApiEntryPoint.class::isInstance)
                    .map(ApiEntryPoint.class::cast)
                    .map(ApiMethodSnapshot::from)
                    .toList();
            return new ApiClassSnapshot(entryPointClass.className(), entryPointClass.packagePath(),
                    entryPointClass.basePath(), entryPointClass.description(), apis);
        }

        ApiClassSnapshot normalized() {
            List<ApiMethodSnapshot> sortedApis = apis.stream()
                    .map(ApiMethodSnapshot::normalized)
                    .sorted(Comparator.comparing(ApiMethodSnapshot::apiUrl)
                            .thenComparing(ApiMethodSnapshot::name))
                    .toList();
            return new ApiClassSnapshot(className, toPortablePath(packagePath), basePath,
                    collapseWhitespace(description), sortedApis);
        }
    }

    record ApiMethodSnapshot(String name, String description, EntryPointType type,
            String apiUrl, List<String> apiType, List<String> swaggerDesc) {

        static ApiMethodSnapshot from(ApiEntryPoint api) {
            return new ApiMethodSnapshot(api.name(), api.description(), api.type(),
                    api.apiUrl(), api.apiType(), api.swaggerDesc());
        }

        /** swaggerDesc 由 HashSet 產生，順序不穩定，必須排序 */
        ApiMethodSnapshot normalized() {
            return new ApiMethodSnapshot(name, collapseWhitespace(description), type, apiUrl,
                    sortedCopy(apiType), sortedCopy(swaggerDesc));
        }
    }

    /**
     * trie 查詢結果
     *
     * matches 保留 trie 回傳順序，該順序由 ApiTrieService 的 comparator 決定且本身即是契約，重排會蓋掉迴歸
     */
    record TrieLookupSnapshot(String request, List<ApiRouteCandidate> matches) {
    }

    /** trie 查詢探針，httpMethod 留白代表不指定 verb */
    record TrieLookupProbe(String httpMethod, String requestPath) {

        TrieLookupSnapshot resolve(ApiTrieService apiTrieService, String repoId) {
            List<ApiRouteCandidate> matches = apiTrieService
                    .lookupCandidates(requestPath, httpMethod, repoId).stream()
                    .map(TrieLookupProbe::toCandidate)
                    .toList();
            return new TrieLookupSnapshot(request(), matches);
        }

        String request() {
            return (StringUtils.hasText(httpMethod) ? httpMethod : "*") + " " + requestPath;
        }

        private static ApiRouteCandidate toCandidate(ApiEntryPointRef ref) {
            return new ApiRouteCandidate(ref.repoId(), ref.httpMethod(), ref.routeTemplate(),
                    ref.packageName(), ref.className(), ref.methodName());
        }
    }

    record SqlEvidenceGolden(String fixture, List<SqlEvidenceSnapshot> evidence)
            implements GoldenSnapshot {

        @Override
        public SqlEvidenceGolden normalized(Path fixtureRoot) {
            List<SqlEvidenceSnapshot> sortedEvidence = evidence.stream()
                    .map(snapshot -> snapshot.normalized(fixtureRoot))
                    .sorted(Comparator.comparing(SqlEvidenceSnapshot::packageName)
                            .thenComparing(SqlEvidenceSnapshot::className)
                            .thenComparing(SqlEvidenceSnapshot::methodName)
                            .thenComparing(snapshot -> String.join(",", snapshot.paramTypes())))
                    .toList();
            return new SqlEvidenceGolden(fixture, sortedEvidence);
        }
    }

    /**
     * 單一 mapper 方法的 SQL evidence
     *
     * resolvedSql 是 ClassMetadata 實際交給分析器的值，annotation 優先於 XML
     * mapperXmlSql 是 XML mapper 查得的值，兩者並列即可看出優先序
     */
    record SqlEvidenceSnapshot(String packageName, String className, String filePath,
            String methodName, List<String> paramTypes, List<String> annotations,
            String resolvedSql, String mapperXmlSql) {

        SqlEvidenceSnapshot normalized(Path fixtureRoot) {
            return new SqlEvidenceSnapshot(packageName, className,
                    toFixtureRelativePath(filePath, fixtureRoot), methodName,
                    paramTypes,
                    sortedCopy(annotations),
                    resolvedSql, mapperXmlSql);
        }
    }

    // --- Normalization helpers ---

    private static String collapseWhitespace(String text) {
        return Objects.isNull(text) ? null : text.replaceAll("\\s+", " ").trim();
    }

    private static List<String> sortedCopy(List<String> values) {
        return Objects.isNull(values) ? null : values.stream().sorted().toList();
    }

    private static String toFixtureRelativePath(String filePath, Path fixtureRoot) {
        String portable = toPortablePath(filePath);
        String rootPrefix = toPortablePath(fixtureRoot.toString());
        return portable.startsWith(rootPrefix)
                ? FIXTURE_PLACEHOLDER + portable.substring(rootPrefix.length())
                : portable;
    }

    private static String toPortablePath(String path) {
        return Objects.isNull(path) ? null : path.replace(File.separatorChar, '/');
    }

    private static String toClassName(Path javaFile) {
        String fileName = javaFile.getFileName().toString();
        return fileName.substring(0, fileName.length() - JAVA_SUFFIX.length());
    }

    private static String toPackageName(Path sourceRoot, Path javaFile) {
        Path relativeDir = sourceRoot.relativize(javaFile).getParent();
        return Objects.isNull(relativeDir)
                ? ""
                : toPortablePath(relativeDir.toString()).replace('/', '.');
    }
}
