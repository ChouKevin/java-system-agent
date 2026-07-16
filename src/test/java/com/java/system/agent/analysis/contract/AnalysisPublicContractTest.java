package com.java.system.agent.analysis.contract;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.RepoDescriptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisPublicContractTest {

    /**
     * AnalysisService 的公開方法簽章
     *
     * 這 11 個方法是 api/ 與 ai/ 的契約
     * 遠端遷移時 reloadRepoAfter 會被移除,屆時本清單須刻意更新而非默默漂移
     */
    private static final List<String> EXPECTED_SIGNATURES = List.of(
            "com.java.system.agent.analysis.model.FlattenedCallGraph analyzeMethod("
                    + "java.lang.String,java.lang.String,java.lang.String,java.lang.String)",
            "com.java.system.agent.analysis.model.AnalysisResult<com.java.system.agent.analysis.model.FlattenedCallGraph> "
                    + "analyzeMethodStructured(java.lang.String,java.lang.String,java.lang.String,java.lang.String)",
            "com.java.system.agent.analysis.model.ExplainableCallGraph analyzeMethodExplainable("
                    + "java.lang.String,java.lang.String,java.lang.String,java.lang.String)",
            "com.java.system.agent.analysis.model.AnalysisResult<com.java.system.agent.analysis.model.ExplainableCallGraph> "
                    + "analyzeMethodExplainableStructured(java.lang.String,java.lang.String,java.lang.String,java.lang.String)",
            "java.util.List<com.java.system.agent.analysis.model.EntryPointClass> scanEntryPoints("
                    + "java.lang.String,com.java.system.agent.analysis.model.EntryPointType[])",
            "java.util.List<com.java.system.agent.analysis.model.ApiRef> lookupApi("
                    + "java.lang.String,java.lang.String)",
            "java.util.List<com.java.system.agent.analysis.model.ApiRouteCandidate> lookupApiCandidates("
                    + "java.lang.String,java.lang.String,java.lang.String)",
            "java.util.List<com.java.system.agent.analysis.model.ApiRouteCandidate> suggestApiCandidates("
                    + "java.lang.String,java.lang.String,java.lang.String,int)",
            "void reloadRepo(java.lang.String)",
            "java.lang.String reloadRepoAfter(java.lang.String,java.util.function.Supplier<java.lang.String>)",
            "java.util.List<com.java.system.agent.analysis.model.RepoDescriptor> allRepos()");

    @Test
    void should_expose_exactly_the_frozen_public_surface() {
        List<String> actual = Arrays.stream(AnalysisService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(AnalysisPublicContractTest::describe)
                .sorted()
                .toList();

        assertThat(actual)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SIGNATURES.stream().sorted().toList());
    }

    /**
     * RepoDescriptor 是 GET /git/repos 的回應型別
     *
     * sourceRoot 是本地檔案路徑,遠端遷移時會移除 — 屆時本測試必須刻意更新
     */
    @Test
    void should_keep_repo_descriptor_shape_until_deliberately_changed() {
        List<String> components = Arrays.stream(RepoDescriptor.class.getRecordComponents())
                .map(component -> component.getType().getSimpleName() + " " + component.getName())
                .toList();

        assertThat(components).containsExactly(
                "String repoId",
                "String name",
                "String description",
                "Path sourceRoot");
    }

    private static String describe(Method method) {
        String parameters = Arrays.stream(method.getGenericParameterTypes())
                .map(Type::getTypeName)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return method.getGenericReturnType().getTypeName() + " " + method.getName() + "(" + parameters + ")";
    }
}
