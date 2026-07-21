package com.java.system.agent.demo;

import com.java.system.agent.analysis.callgraph.CallGraphBuilder;
import com.java.system.agent.analysis.callgraph.CallGraphClassifier;
import com.java.system.agent.analysis.callgraph.CallGraphExplanationMapper;
import com.java.system.agent.analysis.callgraph.DtoAnalyzer;
import com.java.system.agent.analysis.callgraph.JavaCallGraphAnalyzer;
import com.java.system.agent.analysis.model.AnalysisMetadata;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisStatus;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.type.ClassMetadataService;
import com.java.system.agent.analysis.type.MapperXmlSqlExtractor;
import com.java.system.agent.analysis.type.ScopeTypeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 示範 repo 契約:本 repo 以自身作為示範分析對象
 * 文件位於 knowledge/repos/java-system-agent,原始碼即本工作目錄
 * (實際部署時由 git.repos 註冊並 clone 至 repos/java-system-agent)
 */
class DemoRepositoryContractTest {

    private static final String REPO_ID = "java-system-agent";

    /** 原始碼:測試直接讀取本 repo 工作目錄,內容等同 runtime 的 clone */
    private static final Path REPO_ROOT = Path.of(".");

    /** 業務文件:agent 自有、手工維護,不隨原始碼移動 */
    private static final Path DOC_ROOT = Path.of("knowledge", "repos", REPO_ID);

    private static final Path SERVICE_MAP = Path.of("knowledge", "service-map.md");
    private static final Pattern BUSINESS_GROUP_LINK = Pattern.compile(
            "\\| `([^`]+)` \\|[^\\n]+\\| \\[[^]]+]\\((business-groups/[^)]+)\\) \\|");
    private static final Pattern SOURCE_LOOKUP_ROW = Pattern.compile(
            "\\| `([^`]+)` \\| `([^`]+)` \\| `([^`]+)` \\| `([^`]+)` \\|");

    private JavaCallGraphAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        SourceRootResolver sourceRootResolver = new SourceRootResolver();
        ProjectParserService projectParserService = new ProjectParserService(sourceRootResolver);
        ClassMetadataService classMetadataService = new ClassMetadataService(
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
    }

    @Test
    void serviceMap_should_list_demo_repository() throws IOException {
        String serviceMap = Files.readString(SERVICE_MAP);

        assertThat(serviceMap).contains("`java-system-agent`");
        assertThat(serviceMap).contains("read_service_map");
        assertThat(serviceMap).contains("read_business_map");
        assertThat(serviceMap).contains("read_business_group_doc");
        assertThat(serviceMap).contains("find_call_graph");
    }

    @Test
    void businessMap_should_link_to_existing_group_documents() throws IOException {
        String businessMap = Files.readString(DOC_ROOT.resolve("business-map.md"));
        List<BusinessGroupDoc> groupDocs = parseBusinessGroupDocs(businessMap);

        assertThat(groupDocs)
                .extracting(BusinessGroupDoc::groupName)
                .containsExactly("repo-management", "code-analysis", "slack-agent");
        for (BusinessGroupDoc groupDoc : groupDocs) {
            assertThat(DOC_ROOT.resolve(groupDoc.relativePath()))
                    .as("business group document exists: %s", groupDoc.relativePath())
                    .exists()
                    .isRegularFile();
        }
    }

    @Test
    void businessGroupSourceLookup_should_point_to_analyzable_entryPoints() throws IOException {
        String businessMap = Files.readString(DOC_ROOT.resolve("business-map.md"));
        List<BusinessGroupDoc> groupDocs = parseBusinessGroupDocs(businessMap);

        for (BusinessGroupDoc groupDoc : groupDocs) {
            String groupContent = Files.readString(DOC_ROOT.resolve(groupDoc.relativePath()));
            List<SourceLookup> lookups = parseSourceLookups(groupContent);
            assertThat(lookups)
                    .as("source lookup rows for %s", groupDoc.groupName())
                    .isNotEmpty();
            for (SourceLookup lookup : lookups) {
                assertSourceExists(lookup);
                assertAnalyzerCanReadEntryPoint(lookup);
            }
        }
    }

    @Test
    void readme_should_document_how_to_add_another_repository() throws IOException {
        String readme = Files.readString(Path.of("README.md"));

        assertThat(readme).contains("## Adding Another Repository");
        assertThat(readme).contains("knowledge/repos/{repoId}/business-map.md");
        assertThat(readme).contains("knowledge/repos/{repoId}/business-groups/{groupName}.md");
        assertThat(readme).contains("read_service_map -> read_business_map -> read_business_group_doc -> find_call_graph");
    }

    @Test
    void should_document_api_tool_and_evidence_policy_when_readme_describes_tool_chain()
            throws Exception {
        String readme = Files.readString(Path.of("README.md"));

        assertThat(readme).contains("find_api_call_graph");
        assertThat(readme).contains("path param");
        assertThat(readme).contains("code evidence");
        assertThat(readme).contains("NOT_FOUND", "AMBIGUOUS");
    }

    @Test
    void should_document_terminal_rest_precedence_when_code_analysis_describes_trie_matching()
            throws Exception {
        String codeAnalysis = Files.readString(
                Path.of("docs", "business-groups", "code-analysis.md"));

        assertThat(codeAnalysis).contains("canonical token `{**}`");
        assertThat(codeAnalysis).contains("零個或多個剩餘 segment");
        assertThat(codeAnalysis).contains(
                "exact → `{*}`（含 backtracking）→ terminal `{**}`");
    }

    private List<BusinessGroupDoc> parseBusinessGroupDocs(String businessMap) {
        Matcher matcher = BUSINESS_GROUP_LINK.matcher(businessMap);
        return matcher.results()
                .map(result -> new BusinessGroupDoc(result.group(1), result.group(2)))
                .toList();
    }

    private List<SourceLookup> parseSourceLookups(String groupContent) {
        Matcher matcher = SOURCE_LOOKUP_ROW.matcher(groupContent);
        return matcher.results()
                .map(result -> new SourceLookup(
                        result.group(1),
                        result.group(2),
                        result.group(3),
                        result.group(4)))
                .filter(SourceLookup::isDemoRepo)
                .toList();
    }

    private void assertSourceExists(SourceLookup lookup) {
        Path sourceFile = sourceFile(lookup);
        assertThat(sourceFile)
                .as("source file exists for %s#%s", lookup.className(), lookup.methodSignature())
                .exists()
                .isRegularFile();
    }

    private void assertAnalyzerCanReadEntryPoint(SourceLookup lookup) {
        Path sourceFile = sourceFile(lookup);
        String relativePath = REPO_ROOT.relativize(sourceFile).toString();
        AnalysisResult<ExplainableCallGraph> result = analyzer.analyzeExplainableResult(
                lookup.repoId(),
                REPO_ROOT,
                relativePath,
                lookup.methodSignature(),
                AnalysisMetadata.now(
                        lookup.repoId(),
                        lookup.packageName(),
                        lookup.className(),
                        lookup.methodSignature()));

        assertThat(result.status())
                .as("analysis status for %s#%s", lookup.className(), lookup.methodSignature())
                .isNotEqualTo(AnalysisStatus.FAILED);
        assertThat(result.data())
                .as("analysis data for %s#%s", lookup.className(), lookup.methodSignature())
                .isNotNull();
    }

    private Path sourceFile(SourceLookup lookup) {
        String packagePath = lookup.packageName().replace('.', '/');
        return REPO_ROOT.resolve("src/main/java")
                .resolve(packagePath)
                .resolve(lookup.className() + ".java");
    }

    private record BusinessGroupDoc(String groupName, String relativePath) {
    }

    private record SourceLookup(
            String repoId,
            String packageName,
            String className,
            String methodSignature) {

        boolean isDemoRepo() {
            return StringUtils.hasText(repoId) && REPO_ID.equals(repoId);
        }
    }
}
