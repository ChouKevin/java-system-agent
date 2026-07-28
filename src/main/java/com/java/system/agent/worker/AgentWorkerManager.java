package com.java.system.agent.worker;

import com.java.system.agent.inbox.port.in.ProcessNextDeliveryUseCase;
import com.java.system.agent.inbox.port.in.ProcessNextInboxUseCase;
import com.java.system.agent.inbox.port.in.RecoverInterruptedWorkUseCase;
import com.java.system.agent.inbox.port.in.StopClaimingUseCase;
import com.java.system.agent.inbox.port.in.AgentOperationsSnapshot;
import com.java.system.agent.inbox.port.in.ClaimRecoveryFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在 Slack Agent profile 中管理單一 inbox 與單一 delivery fixed-delay worker 的 lifecycle
 */
public final class AgentWorkerManager implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentWorkerManager.class);
    private static final int PHASE = 100;

    private final RecoverInterruptedWorkUseCase recovery;
    private final ProcessNextInboxUseCase inboxProcessor;
    private final ProcessNextDeliveryUseCase deliveryProcessor;
    private final StopClaimingUseCase claimAdmission;
    private final Duration inboxPollInterval;
    private final Duration deliveryPollInterval;
    private final Duration shutdownGracePeriod;
    private final Clock clock;
    private final ScheduledExecutorService inboxExecutor;
    private final ScheduledExecutorService deliveryExecutor;
    private final AtomicBoolean acceptingClaims = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean inboxBusy = new AtomicBoolean();
    private final AtomicBoolean deliveryBusy = new AtomicBoolean();

    private volatile ScheduledFuture<?> inboxSchedule;
    private volatile ScheduledFuture<?> deliverySchedule;

    public AgentWorkerManager(
            RecoverInterruptedWorkUseCase recovery,
            ProcessNextInboxUseCase inboxProcessor,
            ProcessNextDeliveryUseCase deliveryProcessor,
            Duration inboxPollInterval,
            Duration deliveryPollInterval,
            Duration shutdownGracePeriod,
            StopClaimingUseCase claimAdmission) {
        this(
                recovery,
                inboxProcessor,
                deliveryProcessor,
                inboxPollInterval,
                deliveryPollInterval,
                shutdownGracePeriod,
                claimAdmission,
                Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(namedThreadFactory("agent-inbox-worker")),
                Executors.newSingleThreadScheduledExecutor(namedThreadFactory("agent-delivery-worker")));
    }

    AgentWorkerManager(
            RecoverInterruptedWorkUseCase recovery,
            ProcessNextInboxUseCase inboxProcessor,
            ProcessNextDeliveryUseCase deliveryProcessor,
            Duration inboxPollInterval,
            Duration deliveryPollInterval,
            Duration shutdownGracePeriod,
            StopClaimingUseCase claimAdmission,
            Clock clock,
            ScheduledExecutorService inboxExecutor,
            ScheduledExecutorService deliveryExecutor) {
        this.recovery = Objects.requireNonNull(recovery, "recovery use case must not be null");
        this.inboxProcessor = Objects.requireNonNull(inboxProcessor, "inbox processor must not be null");
        this.deliveryProcessor = Objects.requireNonNull(deliveryProcessor, "delivery processor must not be null");
        this.claimAdmission = Objects.requireNonNull(claimAdmission, "claim admission use case must not be null");
        this.inboxPollInterval = requirePositive(inboxPollInterval, "inbox poll interval");
        this.deliveryPollInterval = requirePositive(deliveryPollInterval, "delivery poll interval");
        this.shutdownGracePeriod = requirePositive(shutdownGracePeriod, "shutdown grace period");
        this.clock = Objects.requireNonNull(clock, "worker clock must not be null");
        this.inboxExecutor = Objects.requireNonNull(inboxExecutor, "inbox executor must not be null");
        this.deliveryExecutor = Objects.requireNonNull(deliveryExecutor, "delivery executor must not be null");
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            recovery.recoverInterrupted(now());
            acceptingClaims.set(true);
            inboxSchedule = inboxExecutor.scheduleWithFixedDelay(
                    this::processInboxOnce,
                    0,
                    inboxPollInterval.toNanos(),
                    TimeUnit.NANOSECONDS);
            deliverySchedule = deliveryExecutor.scheduleWithFixedDelay(
                    this::processDeliveryOnce,
                    0,
                    deliveryPollInterval.toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (RuntimeException exception) {
            running.set(false);
            acceptingClaims.set(false);
            cancelSchedules();
            shutdownExecutors();
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "lifecycle stop callback must not be null");
        try {
            claimAdmission.stopClaiming();
            acceptingClaims.set(false);
            running.set(false);
            cancelSchedules();
            shutdownExecutors();
            awaitGracePeriod();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * 回報目前 worker 是否正在執行任何一個 bounded work loop
     */
    public AgentOperationsSnapshot.WorkerState operationsState() {
        return inboxBusy.get() || deliveryBusy.get()
                ? AgentOperationsSnapshot.WorkerState.BUSY
                : AgentOperationsSnapshot.WorkerState.IDLE;
    }

    private void processInboxOnce() {
        if (!acceptingClaims.get()) {
            return;
        }
        inboxBusy.set(true);
        try {
            inboxProcessor.processNext(now());
        } catch (ClaimRecoveryFailureException exception) {
            stopAfterUnrecoverableClaim("INBOX_CLAIM_RECOVERY", exception);
        } catch (RuntimeException exception) {
            logLoopFailure("INBOX_PROCESSING", exception);
        } finally {
            inboxBusy.set(false);
        }
    }

    private void processDeliveryOnce() {
        if (!acceptingClaims.get()) {
            return;
        }
        deliveryBusy.set(true);
        try {
            deliveryProcessor.processNext(now());
        } catch (ClaimRecoveryFailureException exception) {
            stopAfterUnrecoverableClaim("DELIVERY_CLAIM_RECOVERY", exception);
        } catch (RuntimeException exception) {
            logLoopFailure("DELIVERY_PROCESSING", exception);
        } finally {
            deliveryBusy.set(false);
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private void stopAfterUnrecoverableClaim(String category, ClaimRecoveryFailureException exception) {
        claimAdmission.stopClaiming();
        acceptingClaims.set(false);
        running.set(false);
        cancelSchedules();
        shutdownExecutors();
        logLoopFailure(category, exception);
    }

    private void cancelSchedules() {
        cancel(inboxSchedule);
        cancel(deliverySchedule);
    }

    private static void cancel(ScheduledFuture<?> schedule) {
        if (Objects.nonNull(schedule)) {
            schedule.cancel(false);
        }
    }

    private void shutdownExecutors() {
        inboxExecutor.shutdown();
        deliveryExecutor.shutdown();
    }

    private void awaitGracePeriod() {
        long deadline = Math.addExact(System.nanoTime(), shutdownGracePeriod.toNanos());
        await(inboxExecutor, remainingNanos(deadline), "INBOX_SHUTDOWN");
        await(deliveryExecutor, remainingNanos(deadline), "DELIVERY_SHUTDOWN");
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static void await(ScheduledExecutorService executor, long graceNanos, String category) {
        try {
            executor.awaitTermination(graceNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logLoopFailure(category, exception);
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Duration verifiedDuration = Objects.requireNonNull(duration, name + " must not be null");
        if (verifiedDuration.isNegative() || verifiedDuration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return verifiedDuration;
    }

    private static ThreadFactory namedThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void logLoopFailure(String category, Exception exception) {
        WorkerDiagnosticException diagnostic = new WorkerDiagnosticException(exception);
        LOGGER.warn("agent worker category={} exceptionType={}", category, exception.getClass().getName(), diagnostic);
    }

    /**
     * 移除原始訊息內容後保留例外型別與 stack 的 worker 診斷
     */
    private static final class WorkerDiagnosticException extends RuntimeException {

        private WorkerDiagnosticException(Exception exception) {
            super("exception type=" + exception.getClass().getName());
            setStackTrace(exception.getStackTrace());
        }
    }
}
