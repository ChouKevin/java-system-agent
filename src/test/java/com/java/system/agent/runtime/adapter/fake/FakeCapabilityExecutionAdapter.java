package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 以精確 capability invocation 登錄原始執行結果且不提供 fallback 的測試替身
 */
public final class FakeCapabilityExecutionAdapter implements CapabilityExecutionPort {

    private final Map<CapabilityInvocation, CapabilityExecutionResult> results = new LinkedHashMap<>();
    private final List<CapabilityInvocation> invocations = new ArrayList<>();

    public synchronized FakeCapabilityExecutionAdapter register(
            CapabilityInvocation invocation,
            CapabilityExecutionResult result) {
        results.put(Objects.requireNonNull(invocation, "capability invocation must not be null"),
                Objects.requireNonNull(result, "capability execution result must not be null"));
        return this;
    }

    @Override
    public synchronized CapabilityExecutionResult execute(CapabilityInvocation invocation) {
        CapabilityInvocation checkedInvocation = Objects.requireNonNull(
                invocation, "capability invocation must not be null");
        invocations.add(checkedInvocation);
        CapabilityExecutionResult result = results.get(checkedInvocation);
        if (Objects.isNull(result)) {
            throw new IllegalArgumentException("no fake capability result is registered for the invocation");
        }
        return result;
    }

    public synchronized List<CapabilityInvocation> invocations() {
        return List.copyOf(invocations);
    }
}
