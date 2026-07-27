package com.java.system.agent.capability.dispatch;

import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 依 runtime 已驗證 capability descriptor 精確委派執行器的 outbound adapter
 */
public final class CapabilityExecutionDispatcher implements CapabilityExecutionPort {

    private static final Logger LOGGER = Logger.getLogger(CapabilityExecutionDispatcher.class.getName());

    private final CapabilityExecutorRegistry registry;

    public CapabilityExecutionDispatcher(CapabilityExecutorRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "capability executor registry must not be null");
    }

    @Override
    public CapabilityExecutionResult execute(CapabilityInvocation invocation) {
        Objects.requireNonNull(invocation, "capability invocation must not be null");
        long startedNanos = System.nanoTime();
        String resultCategory = "CONTRACT_EXCEPTION";
        String executorClass = "UNREGISTERED";
        try {
            CapabilityExecutor executor = registry.executors().get(invocation.capability());
            if (Objects.isNull(executor)) {
                throw new CapabilityExecutionContractException("validated capability has no registered executor");
            }
            executorClass = executor.getClass().getName();
            CapabilityExecutionResult result = executor.execute(invocation);
            if (Objects.isNull(result)) {
                throw new CapabilityExecutionContractException("capability executor must return a result");
            }
            resultCategory = resultCategory(result);
            return result;
        } catch (CapabilityExecutionContractException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            resultCategory = "UNEXPECTED_EXCEPTION";
            throw exception;
        } finally {
            logOperation(invocation, executorClass, resultCategory, startedNanos);
        }
    }

    private static String resultCategory(CapabilityExecutionResult result) {
        if (result instanceof CapabilityExecutionResult.Succeeded) {
            return "SUCCEEDED";
        }
        return ((CapabilityExecutionResult.Failed) result).failure().code().name();
    }

    private static void logOperation(
            CapabilityInvocation invocation,
            String executorClass,
            String resultCategory,
            long startedNanos) {
        Level level = "SUCCEEDED".equals(resultCategory) ? Level.INFO : Level.WARNING;
        LOGGER.log(level,
                "capability dispatch capability={0} version={1} executorClass={2} resultCategory={3} elapsedMs={4}",
                new Object[]{
                        invocation.capability().name(),
                        invocation.capability().version(),
                        executorClass,
                        resultCategory,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)});
    }
}
