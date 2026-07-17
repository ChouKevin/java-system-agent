package com.java.semantic.repository.application;

import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.repository.port.GitRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryConcurrencyTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("test-repo");
    private static final RepositoryRevision SHA_ONE = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final RepositoryRevision SHA_TWO = RepositoryRevision.ofSha(
            "2222222222222222222222222222222222222222");

    @TempDir
    private Path tempDirectory;

    @Test
    void should_make_a_mutation_wait_when_a_reader_holds_the_snapshot() throws Exception {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofSeconds(2));
        service.ensure(REPOSITORY_ID);
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch readerMayFinish = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RepositorySnapshot> reader = pool.submit(() -> service.withSnapshot(
                    REPOSITORY_ID, Optional.empty(), snapshot -> {
                        readerStarted.countDown();
                        await(readerMayFinish);
                        return snapshot;
                    }));
            assertThat(readerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<RepositoryStatus> mutation = pool.submit(
                    () -> service.sync(REPOSITORY_ID, Optional.empty()));

            assertThatThrownBy(() -> mutation.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            readerMayFinish.countDown();
            assertThat(reader.get(5, TimeUnit.SECONDS).revision()).isEqualTo(SHA_ONE);
            assertThat(mutation.get(5, TimeUnit.SECONDS).currentRevision())
                    .contains(SHA_TWO);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_make_a_reader_wait_when_a_mutation_holds_the_write_lock() throws Exception {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofSeconds(2));
        service.ensure(REPOSITORY_ID);
        git.blockNextMutation();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RepositoryStatus> mutation = pool.submit(
                    () -> service.sync(REPOSITORY_ID, Optional.empty()));
            assertThat(git.awaitMutationStart()).isTrue();

            Future<RepositorySnapshot> reader = pool.submit(() -> service.withSnapshot(
                    REPOSITORY_ID, Optional.empty(), snapshot -> snapshot));

            assertThatThrownBy(() -> reader.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            git.releaseMutation();
            assertThat(mutation.get(5, TimeUnit.SECONDS).currentRevision()).contains(SHA_TWO);
            assertThat(reader.get(5, TimeUnit.SECONDS).revision()).isEqualTo(SHA_TWO);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_release_the_read_lock_when_the_operation_throws() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);

        assertThatThrownBy(() -> service.withSnapshot(REPOSITORY_ID, Optional.empty(), snapshot -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(service.status(REPOSITORY_ID).currentRevision()).contains(SHA_ONE);
    }

    @Test
    void should_release_the_write_lock_when_the_mutation_throws() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        git.failNextMutation();

        assertThatThrownBy(() -> service.sync(REPOSITORY_ID, Optional.empty()))
                .isInstanceOf(RepositoryMutationException.class);

        assertThat(service.sync(REPOSITORY_ID, Optional.empty()).currentRevision())
                .contains(SHA_TWO);
    }

    @Test
    void should_return_a_conflict_when_expected_revision_does_not_match() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        RepositoryRevision stale = RepositoryRevision.ofSha("0".repeat(40));

        assertThatThrownBy(() -> service.withSnapshot(
                REPOSITORY_ID, Optional.of(stale), snapshot -> snapshot))
                .isInstanceOf(RepositoryRevisionMismatchException.class);
    }

    @Test
    void should_time_out_rather_than_block_forever_when_the_write_lock_is_held() throws Exception {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(50));
        service.ensure(REPOSITORY_ID);
        RepositoryRuntime runtime = service.registry().get(REPOSITORY_ID);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> holder = pool.submit(() -> {
                Lock writeLock = runtime.lock().writeLock();
                boolean acquired = writeLock.tryLock(1, TimeUnit.SECONDS);
                if (!acquired) {
                    return false;
                }
                try {
                    lockHeld.countDown();
                    await(releaseLock);
                    return true;
                } finally {
                    writeLock.unlock();
                }
            });
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.status(REPOSITORY_ID))
                    .isInstanceOf(RepositoryBusyException.class);

            releaseLock.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_use_a_fair_lock_when_runtime_is_created() {
        DefaultRepositoryApplicationService service = service(
                new FakeGitRepositoryPort(), Duration.ofMillis(100));

        assertThat(service.registry().get(REPOSITORY_ID).lock().isFair()).isTrue();
    }

    @Test
    void should_report_fixture_and_reject_mutations_when_mode_is_local_fixture() {
        RepositoryProperties properties = properties(Duration.ofMillis(100));
        RepositoryProperties.RepositoryConfig config = properties.getRepositories().get("test-repo");
        config.setMode(RepositoryMode.LOCAL_FIXTURE);
        config.setPath(tempDirectory.toString());
        RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);
        DefaultRepositoryApplicationService service = new DefaultRepositoryApplicationService(
                registry, new FakeGitRepositoryPort(), List.of(), properties);

        RepositoryStatus status = service.ensure(REPOSITORY_ID);

        assertThat(status.currentRevision()).contains(RepositoryRevision.fixture());
        assertThatThrownBy(() -> service.sync(REPOSITORY_ID, Optional.empty()))
                .isInstanceOf(ImmutableFixtureException.class);
        assertThatThrownBy(() -> service.checkout(REPOSITORY_ID, "main"))
                .isInstanceOf(ImmutableFixtureException.class);
    }

    private DefaultRepositoryApplicationService service(
            FakeGitRepositoryPort git, Duration lockTimeout) {
        RepositoryProperties properties = properties(lockTimeout);
        RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);
        return new DefaultRepositoryApplicationService(registry, git, List.of(), properties);
    }

    private RepositoryProperties properties(Duration lockTimeout) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(tempDirectory.toString());
        properties.setRepositoryLockTimeout(lockTimeout);
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl("file:///unused");
        properties.getRepositories().put("test-repo", config);
        return properties;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("latch interrupted", exception);
        }
    }

    private static final class FakeGitRepositoryPort implements GitRepositoryPort {

        private boolean cloned;
        private RepositoryRevision revision = SHA_ONE;
        private CountDownLatch mutationStarted = new CountDownLatch(0);
        private CountDownLatch mutationMayFinish = new CountDownLatch(0);
        private boolean failMutation;

        @Override
        public boolean isCloned(Path workingTree) {
            return cloned;
        }

        @Override
        public RepositoryRevision clone(Path workingTree, String url, String branch) {
            cloned = true;
            revision = SHA_ONE;
            return revision;
        }

        @Override
        public RepositoryRevision fetchAndReset(Path workingTree, String branch) {
            mutationStarted.countDown();
            await(mutationMayFinish);
            if (failMutation) {
                failMutation = false;
                throw new RepositoryMutationException("planned mutation failure");
            }
            revision = SHA_TWO;
            return revision;
        }

        @Override
        public RepositoryRevision checkout(Path workingTree, String revisionValue) {
            revision = SHA_TWO;
            return revision;
        }

        @Override
        public RepositoryRevision currentRevision(Path workingTree) {
            return revision;
        }

        @Override
        public String currentBranch(Path workingTree) {
            return "main";
        }

        void blockNextMutation() {
            mutationStarted = new CountDownLatch(1);
            mutationMayFinish = new CountDownLatch(1);
        }

        boolean awaitMutationStart() throws InterruptedException {
            return mutationStarted.await(5, TimeUnit.SECONDS);
        }

        void releaseMutation() {
            mutationMayFinish.countDown();
        }

        void failNextMutation() {
            failMutation = true;
        }
    }
}
