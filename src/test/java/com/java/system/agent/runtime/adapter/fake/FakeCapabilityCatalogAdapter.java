package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.port.out.CapabilityCatalogPort;

import java.util.List;
import java.util.Objects;

/**
 * 回傳固定 capability 目錄且不替 runtime 選擇 capability 的測試替身
 */
public final class FakeCapabilityCatalogAdapter implements CapabilityCatalogPort {

    private final List<CapabilityPolicy> capabilities;

    public FakeCapabilityCatalogAdapter(CapabilityPolicy... capabilities) {
        Objects.requireNonNull(capabilities, "capability catalog must not be null");
        this.capabilities = List.of(capabilities);
    }

    @Override
    public List<CapabilityPolicy> availableCapabilities() {
        return capabilities;
    }
}
