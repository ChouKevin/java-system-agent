package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;

import java.util.List;

/**
 * 列出 answering 可配發 capability 的外部目錄邊界
 */
public interface CapabilityCatalogPort {

    List<CapabilityPolicy> availableCapabilities();
}
