package com.java.system.agent.worker;

import com.java.system.agent.interaction.application.ClaimAdmissionCoordinator;
import com.java.system.agent.interaction.application.DeliveryProcessor;
import com.java.system.agent.interaction.application.DeliveryRetryPolicy;
import com.java.system.agent.interaction.application.DeliveryWorkApplicationService;
import com.java.system.agent.interaction.application.InboxWorkApplicationService;
import com.java.system.agent.interaction.application.SessionInboxProcessor;
import com.java.system.agent.interaction.domain.InboxClaim;
import com.java.system.agent.interaction.domain.InboxMessage;
import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.interaction.domain.InboxMessageStatus;
import com.java.system.agent.interaction.domain.RecoverySummary;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.domain.delivery.DeliveryClaim;
import com.java.system.agent.interaction.domain.delivery.DeliveryId;
import com.java.system.agent.interaction.domain.delivery.DeliveryKind;
import com.java.system.agent.interaction.domain.delivery.DeliveryMessage;
import com.java.system.agent.interaction.domain.delivery.DeliveryStatus;
import com.java.system.agent.interaction.port.in.ProcessNextDeliveryUseCase;
import com.java.system.agent.interaction.port.in.ProcessNextInboxUseCase;
import com.java.system.agent.interaction.port.in.RecoverInterruptedWorkUseCase;
import com.java.system.agent.interaction.port.in.StopClaimingUseCase;
import com.java.system.agent.interaction.port.out.DeliveryOutboxPort;
import com.java.system.agent.interaction.port.out.DeliveryTransportPort;
import com.java.system.agent.interaction.port.out.SessionInboxPort;
import com.java.system.agent.interaction.domain.delivery.DeliveryTransportResult;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent worker 對單筆 polling、啟動復原與關閉界線的 lifecycle 測試
 */
class AgentWorkerManagerTest {

    @Test
    void recoversBeforeStartingIndependentNonOverlappingInboxAndDeliveryLoops() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch inboxEntered = new CountDownLatch(1);
        CountDownLatch deliveryEntered = new CountDownLatch(1);
        CountDownLatch releaseInbox = new CountDownLatch(1);
        AtomicInteger inboxInFlight = new AtomicInteger();
        AtomicInteger maximumInboxInFlight = new AtomicInteger();
        AgentWorkerManager manager = manager(
                recoveredAt -> {
                    events.add("recovery");
                    return new RecoverySummary(0, 0);
                },
                now -> {
                    int current = inboxInFlight.incrementAndGet();
                    maximumInboxInFlight.accumulateAndGet(current, Math::max);
                    inboxEntered.countDown();
                    try {
                        releaseInbox.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    inboxInFlight.decrementAndGet();
                    return Optional.empty();
                },
                now -> {
                    events.add("delivery");
                    deliveryEntered.countDown();
                    return Optional.empty();
                },
                Duration.ofMillis(10));

        manager.start();

        assertThat(inboxEntered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(deliveryEntered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(events.getFirst()).isEqualTo("recovery");
        assertThat(maximumInboxInFlight).hasValue(1);

        releaseInbox.countDown();
        manager.stop();
    }

    @Test
    void stopPreventsNewClaimsWithoutInterruptingTheCurrentAgentCallOrFabricatingCompletion() throws Exception {
        CountDownLatch inboxEntered = new CountDownLatch(1);
        CountDownLatch releaseInbox = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger inboxCalls = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        AgentWorkerManager manager = manager(
                recoveredAt -> new RecoverySummary(0, 0),
                now -> {
                    inboxCalls.incrementAndGet();
                    inboxEntered.countDown();
                    try {
                        releaseInbox.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                    return Optional.empty();
                },
                now -> Optional.empty(),
                Duration.ofMillis(20));

        manager.start();
        assertThat(inboxEntered.await(1, TimeUnit.SECONDS)).isTrue();

        Thread stoppingThread = new Thread(() -> manager.stop(stopped::countDown));
        stoppingThread.start();

        assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(manager.isRunning()).isFalse();
        assertThat(inboxCalls).hasValue(1);
        assertThat(interrupted).isFalse();

        releaseInbox.countDown();
        stoppingThread.join(1_000);
    }

    @Test
    void closesClaimAdmissionBeforeAnAlreadyScheduledWorkerCanReachTheClaimBoundary() throws Exception {
        CountDownLatch workerAdmitted = new CountDownLatch(1);
        CountDownLatch releaseClaimBoundary = new CountDownLatch(1);
        CountDownLatch stopLinearized = new CountDownLatch(1);
        CountDownLatch workerReturned = new CountDownLatch(1);
        AtomicInteger databaseClaims = new AtomicInteger();
        ClaimAdmissionCoordinator claimAdmission = new ClaimAdmissionCoordinator();
        SessionInboxPort inboxPort = mock(SessionInboxPort.class);
        SessionInboxProcessor inboxProcessor = mock(SessionInboxProcessor.class);
        when(inboxPort.claimNext(any())).thenAnswer(invocation -> {
            databaseClaims.incrementAndGet();
            return Optional.empty();
        });
        InboxWorkApplicationService workService = new InboxWorkApplicationService(
                inboxPort, inboxProcessor, claimAdmission);
        AgentWorkerManager manager = manager(
                recoveredAt -> new RecoverySummary(0, 0),
                now -> {
                    workerAdmitted.countDown();
                    try {
                        releaseClaimBoundary.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    workService.processNext(now);
                    workerReturned.countDown();
                    return Optional.empty();
                },
                now -> Optional.empty(),
                Duration.ofMillis(20),
                () -> {
                    claimAdmission.stopClaiming();
                    stopLinearized.countDown();
                });

        manager.start();
        assertThat(workerAdmitted.await(1, TimeUnit.SECONDS)).isTrue();

        Thread stoppingThread = new Thread(manager::stop);
        stoppingThread.start();

        assertThat(stopLinearized.await(1, TimeUnit.SECONDS)).isTrue();
        releaseClaimBoundary.countDown();
        assertThat(workerReturned.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(databaseClaims).hasValue(0);
        stoppingThread.join(1_000);
    }

    @Test
    void returnsAfterTheShutdownGracePeriodWhenAnAlreadyAdmittedClaimStalls() throws Exception {
        CountDownLatch claimStarted = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        CountDownLatch claimReturned = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ClaimAdmissionCoordinator claimAdmission = new ClaimAdmissionCoordinator();
        SessionInboxPort inboxPort = mock(SessionInboxPort.class);
        SessionInboxProcessor inboxProcessor = mock(SessionInboxProcessor.class);
        when(inboxPort.claimNext(any())).thenAnswer(invocation -> {
            claimStarted.countDown();
            try {
                releaseClaim.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            claimReturned.countDown();
            return Optional.empty();
        });
        InboxWorkApplicationService workService = new InboxWorkApplicationService(
                inboxPort, inboxProcessor, claimAdmission);
        AgentWorkerManager manager = manager(
                recoveredAt -> new RecoverySummary(0, 0),
                workService::processNext,
                now -> Optional.empty(),
                Duration.ofMillis(20),
                claimAdmission);

        manager.start();
        assertThat(claimStarted.await(1, TimeUnit.SECONDS)).isTrue();

        Thread stoppingThread = new Thread(() -> manager.stop(stopped::countDown));
        stoppingThread.start();

        try {
            assertThat(stopped.await(300, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(manager.isRunning()).isFalse();
            assertThat(interrupted).isFalse();
        } finally {
            releaseClaim.countDown();
            assertThat(claimReturned.await(1, TimeUnit.SECONDS)).isTrue();
            stoppingThread.join(1_000);
        }
    }

    @Test
    void productionWorkersUseNamedDaemonThreads() throws Exception {
        CountDownLatch inboxEntered = new CountDownLatch(1);
        CountDownLatch deliveryEntered = new CountDownLatch(1);
        AtomicReference<Thread> inboxThread = new AtomicReference<>();
        AtomicReference<Thread> deliveryThread = new AtomicReference<>();
        AgentWorkerManager manager = new AgentWorkerManager(
                recoveredAt -> new RecoverySummary(0, 0),
                now -> {
                    inboxThread.set(Thread.currentThread());
                    inboxEntered.countDown();
                    return Optional.empty();
                },
                now -> {
                    deliveryThread.set(Thread.currentThread());
                    deliveryEntered.countDown();
                    return Optional.empty();
                },
                Duration.ofMillis(50),
                Duration.ofMillis(50),
                Duration.ofMillis(50),
                () -> { });

        manager.start();

        assertThat(inboxEntered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(deliveryEntered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(inboxThread.get()).isNotNull().matches(Thread::isDaemon).extracting(Thread::getName)
                .isEqualTo("agent-inbox-worker");
        assertThat(deliveryThread.get()).isNotNull().matches(Thread::isDaemon).extracting(Thread::getName)
                .isEqualTo("agent-delivery-worker");

        manager.stop();
    }

    @Test
    void declaresTheWorkerPhaseBeforeTheSlackSocketPhase() {
        AgentWorkerManager manager = manager(
                recoveredAt -> new RecoverySummary(0, 0),
                now -> Optional.empty(),
                now -> Optional.empty(),
                Duration.ofMillis(10));

        assertThat(manager.getPhase()).isEqualTo(100);
    }

    @Test
    void stopsBothClaimLoopsWhenTheComposedInboxWorkServiceCannotRecoverItsExactClaim() throws Exception {
        CountDownLatch recoveryAttempted = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger inboxClaims = new AtomicInteger();
        AtomicInteger deliveryClaims = new AtomicInteger();
        ClaimAdmissionCoordinator claimAdmission = new ClaimAdmissionCoordinator();
        SessionInboxPort inboxPort = mock(SessionInboxPort.class);
        SessionInboxProcessor inboxProcessor = mock(SessionInboxProcessor.class);
        InboxClaim inboxClaim = inboxClaim();
        when(inboxPort.claimNext(any())).thenAnswer(invocation -> {
            inboxClaims.incrementAndGet();
            return Optional.of(inboxClaim);
        });
        when(inboxPort.recoverClaim(any(), any())).thenAnswer(invocation -> {
            recoveryAttempted.countDown();
            return false;
        });
        when(inboxProcessor.process(any(), any())).thenThrow(new IllegalStateException("transition failed"));
        InboxWorkApplicationService inboxWorkService = new InboxWorkApplicationService(
                inboxPort, inboxProcessor, claimAdmission);
        DeliveryOutboxPort deliveryOutbox = mock(DeliveryOutboxPort.class);
        when(deliveryOutbox.claimNext(any())).thenAnswer(invocation -> {
            deliveryClaims.incrementAndGet();
            return Optional.empty();
        });
        DeliveryTransportPort deliveryTransport = (message, now) -> new DeliveryTransportResult.Delivered("provider-1");
        DeliveryProcessor deliveryProcessor = new DeliveryProcessor(
                deliveryOutbox,
                deliveryTransport,
                new DeliveryRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), attempt -> Duration.ZERO));
        DeliveryWorkApplicationService deliveryWorkService = new DeliveryWorkApplicationService(
                deliveryOutbox, deliveryProcessor, claimAdmission);
        StopClaimingUseCase stopClaiming = () -> {
            claimAdmission.stopClaiming();
            stopped.countDown();
        };
        AgentWorkerManager manager = manager(
                recoveredAt -> new RecoverySummary(0, 0),
                inboxWorkService::processNext,
                deliveryWorkService::processNext,
                Duration.ofMillis(20),
                stopClaiming);

        manager.start();

        assertThat(recoveryAttempted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(manager.isRunning()).isFalse();
        int inboxClaimsAtStop = inboxClaims.get();

        assertThat(inboxClaimsAtStop).isOne();
        int deliveryClaimsBeforePostStopCalls = deliveryClaims.get();
        assertThat(inboxWorkService.processNext(Instant.EPOCH)).isEmpty();
        assertThat(deliveryWorkService.processNext(Instant.EPOCH)).isEmpty();
        assertThat(inboxClaims).hasValue(inboxClaimsAtStop);
        assertThat(deliveryClaims).hasValue(deliveryClaimsBeforePostStopCalls);
    }

    @Test
    void stopsBothClaimLoopsWhenTheComposedDeliveryWorkServiceCannotRecoverItsExactClaim() throws Exception {
        CountDownLatch recoveryAttempted = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger inboxClaims = new AtomicInteger();
        AtomicInteger deliveryClaims = new AtomicInteger();
        ClaimAdmissionCoordinator claimAdmission = new ClaimAdmissionCoordinator();
        SessionInboxPort inboxPort = mock(SessionInboxPort.class);
        SessionInboxProcessor inboxProcessor = mock(SessionInboxProcessor.class);
        when(inboxPort.claimNext(any())).thenAnswer(invocation -> {
            inboxClaims.incrementAndGet();
            return Optional.empty();
        });
        InboxWorkApplicationService inboxWorkService = new InboxWorkApplicationService(
                inboxPort, inboxProcessor, claimAdmission);
        DeliveryOutboxPort deliveryOutbox = mock(DeliveryOutboxPort.class);
        DeliveryClaim deliveryClaim = deliveryClaim();
        when(deliveryOutbox.claimNext(any())).thenAnswer(invocation -> {
            deliveryClaims.incrementAndGet();
            return Optional.of(deliveryClaim);
        });
        doThrow(new IllegalStateException("transition unavailable"))
                .when(deliveryOutbox).recordDelivered(any(), any(), any());
        when(deliveryOutbox.recoverClaim(any(), any())).thenAnswer(invocation -> {
            recoveryAttempted.countDown();
            throw new IllegalStateException("recovery unavailable");
        });
        DeliveryTransportPort deliveryTransport = (message, now) -> new DeliveryTransportResult.Delivered("provider-1");
        DeliveryProcessor deliveryProcessor = new DeliveryProcessor(
                deliveryOutbox,
                deliveryTransport,
                new DeliveryRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), attempt -> Duration.ZERO));
        DeliveryWorkApplicationService deliveryWorkService = new DeliveryWorkApplicationService(
                deliveryOutbox, deliveryProcessor, claimAdmission);
        StopClaimingUseCase stopClaiming = () -> {
            claimAdmission.stopClaiming();
            stopped.countDown();
        };
        AgentWorkerManager manager = manager(
                recoveredAt -> new RecoverySummary(0, 0),
                inboxWorkService::processNext,
                deliveryWorkService::processNext,
                Duration.ofMillis(20),
                stopClaiming);

        manager.start();

        assertThat(recoveryAttempted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(manager.isRunning()).isFalse();
        int inboxClaimsAtStop = inboxClaims.get();
        int deliveryClaimsAtStop = deliveryClaims.get();

        assertThat(inboxClaimsAtStop).isLessThanOrEqualTo(1);
        assertThat(deliveryClaimsAtStop).isOne();
        assertThat(inboxWorkService.processNext(Instant.EPOCH)).isEmpty();
        assertThat(deliveryWorkService.processNext(Instant.EPOCH)).isEmpty();
        assertThat(inboxClaims).hasValue(inboxClaimsAtStop);
        assertThat(deliveryClaims).hasValue(deliveryClaimsAtStop);
    }

    private static InboxClaim inboxClaim() {
        Instant now = Instant.EPOCH;
        InboxMessage message = new InboxMessage(
                new InboxMessageId("inbox-1"), new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"), new SessionId("session-1"), 0, new AnalysisRunId("run-1"),
                new ParticipantRef("slack", "U123456"), "<@bot> 問題", "問題", InboxMessageStatus.PROCESSING,
                1, now, Optional.of(now), Optional.empty(), Optional.empty());
        return new InboxClaim(message);
    }

    private static DeliveryClaim deliveryClaim() {
        Instant now = Instant.EPOCH;
        DeliveryMessage message = new DeliveryMessage(
                new DeliveryId("delivery-1"), new InboxMessageId("inbox-1"), new AnalysisRunId("run-1"),
                DeliveryKind.RECEIPT, Optional.empty(), Optional.empty(), new SessionSourceRef("slack", "channel:thread"),
                new ParticipantRef("slack", "U123456"), "已接收", DeliveryStatus.PROCESSING, 1,
                now, Optional.empty(), Optional.empty(), now, now);
        return new DeliveryClaim(message);
    }

    private static AgentWorkerManager manager(
            RecoverInterruptedWorkUseCase recovery,
            ProcessNextInboxUseCase inbox,
            ProcessNextDeliveryUseCase delivery,
            Duration gracePeriod) {
        return manager(recovery, inbox, delivery, gracePeriod, () -> { });
    }

    private static AgentWorkerManager manager(
            RecoverInterruptedWorkUseCase recovery,
            ProcessNextInboxUseCase inbox,
            ProcessNextDeliveryUseCase delivery,
            Duration gracePeriod,
            StopClaimingUseCase claimAdmission) {
        ScheduledExecutorService inboxExecutor = new ScheduledThreadPoolExecutor(1);
        ScheduledExecutorService deliveryExecutor = new ScheduledThreadPoolExecutor(1);
        return new AgentWorkerManager(
                recovery,
                inbox,
                delivery,
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                gracePeriod,
                claimAdmission,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                inboxExecutor,
                deliveryExecutor);
    }
}
