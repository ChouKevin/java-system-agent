package com.java.system.agent.analysis;

import com.java.system.agent.analysis.callgraph.JavaCallGraphAnalyzer;
import com.java.system.agent.analysis.entrypoint.EntryPointCacheService;
import com.java.system.agent.analysis.parser.ProjectParserService;
import com.java.system.agent.analysis.parser.SourceRootResolver;
import com.java.system.agent.analysis.port.RepoRegistryPort;
import com.java.system.agent.analysis.port.SourceCodePort;
import com.java.system.agent.analysis.trie.ApiTrieService;
import com.java.system.agent.analysis.type.ClassMetadataService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisServiceReloadTest {

    @Test
    void scanEntryPointsWaitsUntilReloadCompletes() throws Exception {
        SourceCodePort sourceCodePort = mock(SourceCodePort.class);
        when(sourceCodePort.sourceRoot("repo-a")).thenReturn(Path.of("repos", "repo-a"));
        ClassMetadataService classMetadataService = mock(ClassMetadataService.class);
        EntryPointCacheService entryPointCacheService = mock(EntryPointCacheService.class);
        ApiTrieService apiTrieService = mock(ApiTrieService.class);
        ProjectParserService projectParserService = mock(ProjectParserService.class);
        SourceRootResolver sourceRootResolver = mock(SourceRootResolver.class);
        JavaCallGraphAnalyzer analyzer = mock(JavaCallGraphAnalyzer.class);
        AnalysisService service = new AnalysisService(
                analyzer,
                entryPointCacheService,
                apiTrieService,
                classMetadataService,
                projectParserService,
                sourceCodePort,
                mock(RepoRegistryPort.class),
                sourceRootResolver);
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch reloadStarted = new CountDownLatch(1);
        CountDownLatch releaseReload = new CountDownLatch(1);
        CountDownLatch scanEntered = new CountDownLatch(1);
        doAnswer(invocation -> {
            reloadStarted.countDown();
            releaseReload.await(5, TimeUnit.SECONDS);
            order.add("reload-done");
            return null;
        }).when(classMetadataService).reload(any());
        doAnswer(invocation -> {
            scanEntered.countDown();
            return List.of();
        }).when(entryPointCacheService).getEntryPoints(any(), any());

        Thread reloader = new Thread(() -> service.reloadRepo("repo-a"));
        reloader.start();
        assertThat(reloadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Thread analyzerThread = new Thread(() -> {
            service.scanEntryPoints("repo-a");
            order.add("analyze-done");
        });
        analyzerThread.start();
        assertThat(scanEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
        releaseReload.countDown();
        reloader.join(5000);
        analyzerThread.join(5000);

        assertThatCode(() -> {
            if (reloader.isAlive() || analyzerThread.isAlive()) {
                throw new IllegalStateException("threads did not finish");
            }
        }).doesNotThrowAnyException();
        assertThat(order).containsExactly("reload-done", "analyze-done");
    }
}
