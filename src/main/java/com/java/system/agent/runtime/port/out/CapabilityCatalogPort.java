package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;

import java.util.List;

/**
 * 列出 runtime 可配發 capability 的外部目錄邊界
 */
public interface CapabilityCatalogPort {

    List<CapabilityDescriptor> availableCapabilities();
}
